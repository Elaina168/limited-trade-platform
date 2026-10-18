# 第十二阶段：商品一级分类与分页筛选

## 本阶段目标

让用户能够按分类、商品名称找到在售商品，并通过分页分批获取。管理员可以创建一级分类、修改分类名称，并在创建或完整更新商品时指定分类。

原交易、库存、订单幂等、RabbitMQ超时链路沿用；原GET /api/products继续返回五字段数组并使用既有30秒列表缓存。本阶段新增搜索入口直接查询MySQL，不复用原列表缓存，不增加依赖。

## 接口与约定

| 接口 | 权限 | 请求与结果 |
|---|---|---|
| GET /api/categories | 公开 | 按id升序返回分类数组，元素包含id、name |
| POST /api/admin/categories | ADMIN且需CSRF | name；成功201，返回生成id和规范化名称 |
| PUT /api/admin/categories/{id} | ADMIN且需CSRF | name；成功200，名称冲突409，分类不存在404 |
| POST /api/admin/products | 原ADMIN且需CSRF | 原请求增加可选categoryId，商品仍以OFF_SALE创建 |
| PUT /api/admin/products/{id} | 原ADMIN且需CSRF | 原请求增加可选categoryId，省略/null表示清除分类 |
| GET /api/products/search | 公开 | categoryId、keyword可选，page默认1、size默认20最多100 |

分类名去首尾空白、不能为空、输入最长50字符，数据库保证唯一。categoryId提供时必须为正数；写商品时不存在的分类返回404；搜索不存在的分类返回total=0的空页。旧商品保持category_id=NULL。

搜索只返回ON_SALE商品，包含库存为0但仍在售的商品；不按库存自动隐藏。keyword最长100字符，去首尾空白后按名称字面包含匹配，空白表示不筛选。按商品id升序，响应为page、size、total、items；items包含id、name、price、stock、status、categoryId、categoryName。未分类商品的两个分类字段为null。

## 阅读顺序与新增调用链

```text
管理员保存分类
  → AdminCategoryController校验请求
  → CategoryService规范化名称
  → CategoryMapper/XML
  → product_category表的INSERT或UPDATE
  → 返回分类

管理员保存商品
  → 原商品参数校验，新增可选categoryId校验
  → ProductService检查提供的分类是否存在
  → 原商品保存SQL增加category_id
  → 原事务、提交后列表缓存失效按原规则执行

用户搜索商品
  → ProductController.searchProducts校验参数
  → ProductService转义关键词、计算offset
  → 同一只读RR事务内查total及当前页
  → 共用动态SQL条件，分页LEFT JOIN读取分类名称
  → 返回ProductSearchPage
```

只解释新增代码；旧业务只交代到新增点。本文不展示测试源码。

## 1. 新表及商品关联字段


`sql/008_product_category.sql`：

```sql
-- 在007之后，对开发库和测试库各执行一次；旧商品保留未分类，不回填业务数据。
CREATE TABLE product_category (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类编号',
    name VARCHAR(50) NOT NULL COMMENT '一级分类名称',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_category_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一级商品分类';

ALTER TABLE product
    ADD COLUMN category_id BIGINT NULL COMMENT '分类编号，空值表示未分类',
    ADD KEY idx_product_category_status_id (category_id, status, id),
    ADD CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES product_category (id);
```


product_category保存一级分类，不设置parent_id。主键id标识分类，name有唯一索引，避免并发请求生成重名分类。created_at、updated_at由数据库维护，不进入当前分类响应。

商品表增加可空category_id外键：一件商品最多归属一个分类；NULL表示未分类。迁移不为既有商品猜测分类。外键保证非空编号确实对应分类。

(category_id,status,id)复合索引用于带分类及在售条件、按编号排序的查询。本阶段不宣称名称包含匹配使用该索引，也没有进行吞吐测试。

本机开发库、测试库已各执行一次008。不要重跑；首次新环境在007之后执行，已有环境只补尚未执行的迁移。

## 2. 分类对象与输入校验


`src/main/java/com/elaina/limitedtrade/category/Category.java`：

```java
package com.elaina.limitedtrade.category;

public class Category {
    private Long id;
    private String name;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
```


`src/main/java/com/elaina/limitedtrade/category/SaveCategoryRequest.java`：

```java
package com.elaina.limitedtrade.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveCategoryRequest(
        @NotBlank(message = "分类名称不能为空")
        @Size(max = 50, message = "分类名称不能超过50个字符") String name) {
}
```


Category是MyBatis回填和查询使用的JavaBean；SaveCategoryRequest是HTTP请求record。同样的name请求结构用于创建和改名，所以共享这个请求类型。

@NotBlank拒绝null及空白名称，@Size限制长度。Controller的@Valid触发请求校验，之后Service调用strip()去掉首尾空白。

## 3. 分类Mapper与SQL


`src/main/java/com/elaina/limitedtrade/category/CategoryMapper.java`：

```java
package com.elaina.limitedtrade.category;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CategoryMapper {
    List<Category> findAll();
    Category findById(long id);
    int insertCategory(Category category);
    int updateName(@Param("id") long id, @Param("name") String name);
}
```


`src/main/resources/mapper/CategoryMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.elaina.limitedtrade.category.CategoryMapper">
    <select id="findAll" resultType="com.elaina.limitedtrade.category.Category">
        SELECT id, name FROM product_category ORDER BY id ASC
    </select>

    <select id="findById" resultType="com.elaina.limitedtrade.category.Category">
        SELECT id, name FROM product_category WHERE id=#{id}
    </select>

    <insert id="insertCategory" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO product_category (name) VALUES (#{name})
    </insert>

    <update id="updateName">
        UPDATE product_category SET name=#{name} WHERE id=#{id}
    </update>
</mapper>
```


findAll明确按id升序，避免依赖数据库默认返回顺序。findById用于商品写入前验证外部分类编号。insertCategory通过useGeneratedKeys/keyProperty将自增id回填到Category。

updateName返回更新行数，0表示未匹配到该分类。唯一索引才是最终的重名约束，不采用先查名称再插入来替代数据库唯一性。

## 4. 分类Service：规范化、事务与重名冲突


`src/main/java/com/elaina/limitedtrade/category/CategoryService.java`：

```java
package com.elaina.limitedtrade.category;

import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CategoryService {
    private final CategoryMapper mapper;

    public CategoryService(CategoryMapper mapper) {
        this.mapper = mapper;
    }

    public List<Category> listCategories() {
        return mapper.findAll();
    }

    @Transactional
    public Category createCategory(SaveCategoryRequest request) {
        Category category = new Category();
        category.setName(request.name().strip());
        try {
            mapper.insertCategory(category);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分类名称已存在", exception);
        }
        return category;
    }

    @Transactional
    public Category renameCategory(long id, SaveCategoryRequest request) {
        try {
            if (mapper.updateName(id, request.name().strip()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在");
            }
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分类名称已存在", exception);
        }
        return mapper.findById(id);
    }
}
```


创建和改名分别处于@Transactional事务中。创建时先构造Category并规范化名称，INSERT成功后返回带自增编号的对象。改名时先UPDATE，不存在则404，存在则读取更新后的分类返回。

DuplicateKeyException只处理数据库唯一约束冲突，将其转换为HTTP409，并保留原异常作为原因；其他SQL异常继续传播。数据库唯一约束同样能挡住同时到来的重名请求，不依赖Java层检查时序。

listCategories单次读取，不引入分类缓存。改名后商品搜索LEFT JOIN读取当前分类名称，因此不需要另外维护一个分类名称缓存。

## 5. 分类Controller：权限路径与请求响应


`src/main/java/com/elaina/limitedtrade/category/CategoryController.java`：

```java
package com.elaina.limitedtrade.category;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<Category> listCategories() {
        return service.listCategories();
    }
}
```


`src/main/java/com/elaina/limitedtrade/category/AdminCategoryController.java`：

```java
package com.elaina.limitedtrade.category;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/categories")
public class AdminCategoryController {
    private final CategoryService service;

    public AdminCategoryController(CategoryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Category> createCategory(@Valid @RequestBody SaveCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCategory(request));
    }

    @PutMapping("/{id}")
    public Category renameCategory(@PathVariable @Positive long id,
                                   @Valid @RequestBody SaveCategoryRequest request) {
        return service.renameCategory(id, request);
    }
}
```


公开查询走/api/categories，管理写入走/api/admin/categories。@PostMapping创建返回201，@PutMapping改名返回200。@PathVariable @Positive约束路径编号；@Valid @RequestBody约束JSON正文。

权限和CSRF由原SecurityFilterChain统一处理。新增GET /api/categories与GET /api/products/search精确放行；/api/admin/**继续要求ADMIN，默认CSRF防护保留，未放开所有商品子路径。

## 6. 商品关联分类：只看新增点

Product增加以下字段和访问方法，原五参数构造器保留：

```java
private Long categoryId;

public Long getCategoryId() { return categoryId; }
public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
```

CreateProductRequest与UpdateProductRequest都在原字段后增加：

```java
@Positive(message = "分类编号必须大于0")
Long categoryId
```

没有@NotNull，所以允许不提供分类。完整PUT中省略或传null都写成未分类；如果只想保留原分类，调用者需要传回它的categoryId。该约定已进入接口验证和文档。

ProductService新增注入CategoryMapper。在原下单/活动等交易中没有增加分类业务。


ProductService中实际修改的创建/详情更新方法：

```java
    @Transactional
    public Product createProduct(CreateProductRequest request) {
        if (request.categoryId() != null && categoryMapper.findById(request.categoryId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在");
        }
        Product product = new Product(null, request.name(), request.price(), request.stock(), "OFF_SALE");
        product.setCategoryId(request.categoryId());
        productMapper.insertProduct(product);
        return product;
    }

    @Transactional
    public Product updateProduct(long id, UpdateProductRequest request) {
        if (request.categoryId() != null && categoryMapper.findById(request.categoryId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在");
        }
        if (productMapper.updateDetails(id, request) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        productCache.evictAfterCommit();
        return productMapper.findById(id);
    }
```


创建前、更新前的新增if检查请求中提供的分类是否存在；无分类时跳过。创建仍先构造OFF_SALE商品，再通过setCategoryId设置分类，随后原insertProduct保存。

更新仍只修改详情，不改变库存和销售状态；继续调用原productCache.evictAfterCommit()登记提交后失效。分类校验失败发生在UPDATE前，商品名称、价格和分类都不会被修改。

ProductMapper.xml原SQL相应增加：

```xml
<!-- findOnSaleProducts与findById的查询列增加category_id -->
SELECT id, name, price, stock, status, category_id

<!-- insertProduct -->
INSERT INTO product (name, price, stock, status, category_id)
VALUES (#{name}, #{price}, #{stock}, #{status}, #{categoryId})

<!-- updateDetails -->
UPDATE product
SET name = #{request.name}, price = #{request.price},
    category_id = #{request.categoryId}
WHERE id = #{id}
```

上面按修改位置摘录；第一条仅展示SELECT列，不是单独完整查询。库存扣减、归还和状态更新SQL保持原流程。

## 7. 响应对象：旧列表与新分页分别表达


`src/main/java/com/elaina/limitedtrade/product/ProductListItem.java`：

```java
package com.elaina.limitedtrade.product;

import java.math.BigDecimal;

// 固定旧列表的公开字段，新增分类元数据通过分页查询返回。
public record ProductListItem(Long id, String name, BigDecimal price, Integer stock, String status) {
}
```


`src/main/java/com/elaina/limitedtrade/product/ProductSearchItem.java`：

```java
package com.elaina.limitedtrade.product;

import java.math.BigDecimal;

public record ProductSearchItem(Long id, String name, BigDecimal price, Integer stock,
                                String status, Long categoryId, String categoryName) {
}
```


`src/main/java/com/elaina/limitedtrade/product/ProductSearchPage.java`：

```java
package com.elaina.limitedtrade.product;

import java.util.List;

public record ProductSearchPage(int page, int size, long total, List<ProductSearchItem> items) {
}
```


Product增加categoryId后，直接作为旧公开列表返回会多出字段。因此ProductListItem固定旧列表的id/name/price/stock/status五个字段，保持公开响应。

ProductSearchItem是新分页查询的一条结果，额外携带categoryId和categoryName。ProductSearchPage将当前页参数、匹配总条数与当前页items组合在一起，沿用既有管理员订单分页的响应风格。

这是Java record，自动提供构造器和字段访问方法。Mapper分页查询按记录构造器字段顺序选择七个列，未分类项的两个分类值可以为空。

## 8. 商品Controller：新查询入口与旧列表映射


`src/main/java/com/elaina/limitedtrade/product/ProductController.java`：

```java
package com.elaina.limitedtrade.product;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductListItem> listProducts() {
        return productService.listOnSaleProducts().stream()
                .map(product -> new ProductListItem(product.getId(), product.getName(), product.getPrice(),
                        product.getStock(), product.getStatus())).toList();
    }

    @GetMapping("/search")
    public ProductSearchPage searchProducts(
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @Size(max = 100) String keyword) {
        return productService.searchProducts(page, size, categoryId, keyword);
    }
}
```


旧listProducts仍先调用原listOnSaleProducts，保留缓存命中/回源行为；stream().map(...)逐个转换为五字段ProductListItem，toList()返回数组对应的列表。

新searchProducts读取查询参数：@RequestParam默认page=1、size=20；@Positive要求页码、页大小及提供的分类编号大于0；@Max(100)限制单页上限；@Size(max=100)限制关键词。由Spring MVC方法校验处理不合法参数，不在Service重复检查已保证的内部参数。

例如：GET /api/products/search?categoryId=12001&keyword=键盘&page=2&size=10。这里12001仅为说明参数结构的示例编号，不表示开发库已有此分类。

## 9. 搜索Service：关键词字面匹配与分页位置


```java
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductSearchPage searchProducts(int page, int size, Long categoryId, String keyword) {
        // 搜索词按普通文本匹配；先转义转义符自身，再处理LIKE通配符。
        String literalKeyword = keyword == null || keyword.isBlank() ? null
                : keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        long offset = (long) (page - 1) * size;
        long total = productMapper.countSearchProducts(categoryId, literalKeyword);
        List<ProductSearchItem> items = productMapper.searchProducts(categoryId, literalKeyword, offset, size);
        return new ProductSearchPage(page, size, total, items);
    }
```


空或纯空白keyword归一为null，表示不添加名称条件。其他词先strip()，再按顺序转义：!变!!，%变!%，_变!_。SQL随后显式设置ESCAPE '!'，所以用户输入的这些字符按普通文本匹配；不能先替换%再替换!，否则会把刚加上的转义符再次修改。

offset=(page-1)*size。先把page-1转换为long再乘，避免合法大页码发生int溢出。比如page=2、size=10，offset=10，跳过前10条。

count与items共用一个只读REPEATABLE_READ事务，在同一MySQL快照内读取，避免这两个查询之间提交的变动造成同一次响应的统计与列表相互矛盾。不同HTTP分页请求仍是不同快照，本阶段不承诺跨请求在并发增删改下绝不位移，也未引入游标分页。

## 10. 搜索Mapper：共用筛选条件与LEFT JOIN


```xml
    <sql id="searchConditions">
        WHERE p.status = 'ON_SALE'
        <if test="categoryId != null">
            AND p.category_id = #{categoryId}
        </if>
        <if test="keyword != null">
            AND p.name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '!'
        </if>
    </sql>

    <select id="countSearchProducts" resultType="long">
        SELECT COUNT(*) FROM product p
        <include refid="searchConditions"/>
    </select>

    <select id="searchProducts" resultType="com.elaina.limitedtrade.product.ProductSearchItem">
        SELECT p.id, p.name, p.price, p.stock, p.status, p.category_id, c.name AS category_name
        FROM product p LEFT JOIN product_category c ON c.id=p.category_id
        <include refid="searchConditions"/>
        ORDER BY p.id ASC LIMIT #{limit} OFFSET #{offset}
    </select>
```


两个select通过include引用同一searchConditions，始终限制ON_SALE，并按提供的categoryId、keyword组合条件，避免total和items使用不同筛选标准。

#{...}是MyBatis预编译参数，不拼接用户SQL。LIKE外层的两个%实现名称包含匹配，参数里的特殊字符已转义。

LEFT JOIN保留未分类商品：即使category_id=NULL，商品仍可出现在不限定分类的结果中，分类名称为null。按分类查询时WHERE会筛掉其他分类与未分类商品。

ORDER BY p.id ASC使用唯一编号保证固定数据集的分页顺序；LIMIT与OFFSET都是绑定参数。总条数无需JOIN分类表，因为筛选条件只使用商品字段，非空分类由外键约束存在。

## 11. 缓存兼容边界

原key仍为limited-trade:product:on-sale:v1、有效期30秒。新增的categoryId是可选字段，旧缓存JSON没有该字段时，反序列化到Product得到null；旧公开列表映射只取原五字段。新缓存可以含categoryId，但旧公开接口不暴露它，所以这次无需更换原key。

新搜索完全通过MySQL读取，分类名称没有写入旧列表缓存。商品分类改动仍沿用详情更新后的缓存失效；分类改名不需要清理旧列表缓存，因为旧公开列表没有分类名称，搜索又直接查库。

已有列表允许30秒展示旧值的边界保持；分类与名称筛选没有新增缓存一致性问题。

## 12. 验证与本机状态

- 分类API15项、商品分类关联9项、分页搜索28项，加上原商品相关用例，共76项专项通过。
- 原真实Redis集成用例增加3项：旧五字段缓存JSON可读、搜索绕过旧列表缓存、分类字段不泄露到旧公开列表。
- 全量离线package：273项，0失败、0错误、0跳过，BUILD SUCCESS，包含上一阶段218项及本阶段新增55项。真实MySQL、Redis及既有RabbitMQ集成用例均在该轮中执行。
- 独立只读复核未发现重要缺陷。开发库原五表公开字段快照一致，新分类表0行，旧商品仍未分类；测试库六表均0行。JAR未包含本机凭据、测试配置、工具及测试类。
- 临时启动JAR做真实HTTP验证被自动审批拒绝，工具仅返回blocked by policy；本阶段没有这项HTTP运行证据，接口验证采用MockMvc与真实数据库。
- 本阶段未新增依赖、未提交、推送或合并。已有Redis/RabbitMQ改动保留。

本机证据：tools/catalog-category-red.log、catalog-category-green.log、catalog-search-red.log、catalog-search-green.log、catalog-package.log、catalog-before.tsv、catalog-after.tsv、catalog-progress.md，均不进入Git。008只增加表及可空字段，不写入开发分类或商品关联。
