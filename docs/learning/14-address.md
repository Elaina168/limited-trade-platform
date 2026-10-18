# 第十四阶段：用户收货地址

## 目标与整体流程

登录用户维护自己的收货地址：新增、查看、完整修改、删除、设置默认。地址保存到MySQL，退出再登录仍存在。本阶段为后续购物车多商品结算准备地址来源；当前订单还没有地址关联或地址快照。

| 接口 | 成功结果 |
|---|---|
| GET /api/addresses | 200，本人地址数组，默认优先、id升序 |
| POST /api/addresses | 201，创建后的地址 |
| PUT /api/addresses/{id} | 200，完整修改后的地址，默认标记保留 |
| DELETE /api/addresses/{id} | 204，删除本人地址 |
| PUT /api/addresses/{id}/default | 204，将本人目标设为默认，无需正文 |

全部需要登录，写操作需要CSRF。用户编号只从登录身份取得，ADMIN同样只能操作自己的地址。不存在或属于他人的写入目标统一404。

```text
请求 → Security检查登录与CSRF
     → Controller校验正文/路径，从登录身份取userId
     → Service事务：先锁用户行，再读取/修改地址
     → Mapper接口 → XML中的SQL → MySQL
     → AddressResponse → JSON
```

## 1. 数据表：每条地址有自己的编号

[010_user_address.sql](/D:/codex/Java/limited-trade-platform/sql/010_user_address.sql)：

```sql
-- 在009之后，对开发库和测试库各执行一次；只创建空地址表。
CREATE TABLE user_address (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '所属用户编号',
    recipient_name VARCHAR(50) NOT NULL COMMENT '收货人',
    phone VARCHAR(11) NOT NULL COMMENT '大陆手机号',
    province VARCHAR(50) NOT NULL,
    city VARCHAR(50) NOT NULL,
    district VARCHAR(50) NOT NULL,
    detail_address VARCHAR(200) NOT NULL,
    is_default TINYINT NOT NULL DEFAULT 0 COMMENT '默认标记：0否，1是',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_address_owner_default (user_id,is_default,id),
    CONSTRAINT fk_user_address_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT chk_user_address_default CHECK (is_default IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收货地址';
```

`id`标识某一条地址，`user_id`说明归属。一位用户可以有多条地址，所以`user_id`不能唯一。外键确保所属用户存在，不级联删除。

六个内容字段保存收货人、手机号、省市区及详细地址。这里保存文本，不校验行政区真实性，也没有接短信验证。

`is_default`为0或1，分别表示普通地址和默认地址。CHECK只限制单行的值；“每用户最多一个默认”的跨行规则由后面的用户行锁与事务共同维护。不能加`UNIQUE(user_id,is_default)`，否则同一用户连普通地址也只能存一条。

索引`(user_id,is_default,id)`支持先按用户查找、筛选默认项；列表显式使用`ORDER BY is_default DESC,id ASC`，确保默认项在前。同列方向混合的排序是否另用排序操作由优化器决定，不能仅凭这个索引承诺完全免排序。

`created_at`记录创建时间，`updated_at`由数据库在记录实际变化时更新。两个数据库均已执行010，不要重复执行。

## 2. 请求：客户端只提交六个内容字段

[SaveAddressRequest.java](/D:/codex/Java/limited-trade-platform/src/main/java/com/elaina/limitedtrade/address/SaveAddressRequest.java)：

```java
package com.elaina.limitedtrade.address;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SaveAddressRequest(
        @NotBlank @Size(max = 50) String recipientName,
        @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
        @NotBlank @Size(max = 50) String province,
        @NotBlank @Size(max = 50) String city,
        @NotBlank @Size(max = 50) String district,
        @NotBlank @Size(max = 200) String detailAddress) {
}
```

`@NotBlank`拒绝null、空串和纯空白文本，`@Size`限制长度，手机号的`@Pattern`要求严格11位且匹配`1[3-9][0-9]{9}`。手机号带+86或首尾空格也拒绝。

校验发生在Controller的`@Valid`边界。合法文本进入Service后才strip去首尾空白，因此长度限制针对提交内容，不能靠先裁剪绕过。完整PUT必须提供全部六字段。

请求没有`userId`和`defaultAddress`成员；额外提交这两个字段不会改变归属或默认状态。默认切换只走专用接口。

## 3. 内部实体与公开响应

[UserAddress.java](/D:/codex/Java/limited-trade-platform/src/main/java/com/elaina/limitedtrade/address/UserAddress.java)：

```java
package com.elaina.limitedtrade.address;

public class UserAddress {
    private Long id;
    private Long userId;
    private String recipientName;
    private String phone;
    private String province;
    private String city;
    private String district;
    private String detailAddress;
    private boolean defaultAddress;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }

    public String getDetailAddress() { return detailAddress; }
    public void setDetailAddress(String detailAddress) { this.detailAddress = detailAddress; }

    public boolean isDefaultAddress() { return defaultAddress; }
    public void setDefaultAddress(boolean defaultAddress) { this.defaultAddress = defaultAddress; }
}
```

`UserAddress`用于MyBatis查询与插入，包含内部`userId`。隐式无参构造配合setter允许MyBatis填充字段。插入后还会把MySQL生成的主键填回`id`，所以这里使用可变JavaBean。

[AddressResponse.java](/D:/codex/Java/limited-trade-platform/src/main/java/com/elaina/limitedtrade/address/AddressResponse.java)：

```java
package com.elaina.limitedtrade.address;

public record AddressResponse(Long id, String recipientName, String phone, String province,
                              String city, String district, String detailAddress, boolean defaultAddress) {
    public static AddressResponse from(UserAddress address) {
        return new AddressResponse(address.getId(), address.getRecipientName(), address.getPhone(),
                address.getProvince(), address.getCity(), address.getDistrict(), address.getDetailAddress(),
                address.isDefaultAddress());
    }
}
```

`AddressResponse`是对外结果，不包含`userId`。`from`把数据库实体转换为响应；创建、修改和列表复用同一映射。`defaultAddress`是布尔值，JSON中为true或false。

## 4. Controller：确定本人和接收请求

[AddressController.java](/D:/codex/Java/limited-trade-platform/src/main/java/com/elaina/limitedtrade/address/AddressController.java)：

```java
package com.elaina.limitedtrade.address;

import java.util.List;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {
    private final AddressService service;

    public AddressController(AddressService service) {
        this.service = service;
    }

    @GetMapping
    public List<AddressResponse> listAddresses(@AuthenticationPrincipal PlatformUserDetails user) {
        return service.listAddresses(user.getId());
    }

    @PostMapping
    public ResponseEntity<AddressResponse> createAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                                         @Valid @RequestBody SaveAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createAddress(user.getId(), request));
    }

    @PutMapping("/{id}")
    public AddressResponse updateAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                         @PathVariable @Positive long id,
                                         @Valid @RequestBody SaveAddressRequest request) {
        return service.updateAddress(user.getId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                             @PathVariable @Positive long id) {
        service.deleteAddress(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<Void> setDefaultAddress(@AuthenticationPrincipal PlatformUserDetails user,
                                                 @PathVariable @Positive long id) {
        service.setDefaultAddress(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
```

`@RequestMapping`确定统一前缀。五个方法各自接收一种HTTP请求，调用Service，再返回对应状态或对象。

`@AuthenticationPrincipal PlatformUserDetails user`取Spring Security已认证的用户；`user.getId()`作为Service第一个参数。客户端不能靠参数或正文指定另一个用户。

`@Positive long id`要求路径编号为正数；非数字或超出long范围由参数转换拒绝。`@Valid @RequestBody`触发请求record上的约束，非法输入返回400。

创建使用201及响应正文；完整修改直接返回对象，默认200；删除和设置默认返回204空正文。列表空时返回[]。

原安全过滤链只新增下面这一条，放在denyAll之前；原来的CSRF机制继续生效：

```java
.requestMatchers("/api/addresses", "/api/addresses/**").authenticated()
```

## 5. Service：事务、用户行锁与默认规则

[AddressService.java](/D:/codex/Java/limited-trade-platform/src/main/java/com/elaina/limitedtrade/address/AddressService.java)：

```java
package com.elaina.limitedtrade.address;

import java.util.List;
import com.elaina.limitedtrade.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AddressService {
    private final AddressMapper mapper;
    private final AppUserMapper users;

    public AddressService(AddressMapper mapper, AppUserMapper users) {
        this.mapper = mapper;
        this.users = users;
    }

    public List<AddressResponse> listAddresses(long userId) {
        return mapper.findUserAddresses(userId).stream().map(AddressResponse::from).toList();
    }

    @Transactional
    public AddressResponse createAddress(long userId, SaveAddressRequest request) {
        users.lockById(userId);
        UserAddress address = addressDetails(userId, request);
        // 所有写操作共用用户行锁，避免并发首次新增产生多个默认项。
        address.setDefaultAddress(mapper.countUserAddresses(userId) == 0);
        mapper.insertAddress(address);
        return AddressResponse.from(address);
    }

    @Transactional
    public AddressResponse updateAddress(long userId, long id, SaveAddressRequest request) {
        users.lockById(userId);
        UserAddress address = addressDetails(userId, request);
        address.setId(id);
        if (mapper.updateDetails(address) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在");
        }
        return AddressResponse.from(mapper.findOwnedAddress(id, userId));
    }

    @Transactional
    public void deleteAddress(long userId, long id) {
        users.lockById(userId);
        if (mapper.deleteOwnedAddress(id, userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在");
        }
    }

    @Transactional
    public void setDefaultAddress(long userId, long id) {
        users.lockById(userId);
        // 先确认本人目标，再清旧设新；任何SQL失败都由同一事务回滚。
        if (mapper.findOwnedAddress(id, userId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在");
        }
        mapper.clearDefault(userId);
        mapper.markDefault(id, userId);
    }

    private UserAddress addressDetails(long userId, SaveAddressRequest request) {
        UserAddress address = new UserAddress();
        address.setUserId(userId);
        address.setRecipientName(request.recipientName().strip());
        address.setPhone(request.phone());
        address.setProvince(request.province().strip());
        address.setCity(request.city().strip());
        address.setDistrict(request.district().strip());
        address.setDetailAddress(request.detailAddress().strip());
        return address;
    }
}
```

### 5.1 为什么先锁用户

四个写方法都有`@Transactional`，第一条数据库操作都是`users.lockById(userId)`。复用原有AppUserMapper中的SQL：

```sql
SELECT id FROM app_user WHERE id = #{id} FOR UPDATE
```

假设用户同时新增两条地址：没有统一锁时，两次查询可能都看到数量0，于是都成为默认。用户行是这个用户始终存在的共同记录；第一个事务锁住它后，第二个写事务等待，第一个提交释放锁，第二个才继续统计和写入。

所以要让新增、修改、删除、设默认全部遵守同一入口；只给设默认加锁不足以覆盖首次创建的竞争。锁由事务提交或回滚释放，不需要手工unlock。同一用户的订单创建如果也持有该用户锁，地址写入可能等待；其他用户有各自用户行，但不能承诺数据库层完全没有其他短暂等待。

### 5.2 新增地址

```text
锁用户 → 构建并规范化内容 → 查本人地址数
      → 数量为0则默认true，否则false
      → INSERT并回填id → 转为响应 → 提交
```

`countUserAddresses(userId) == 0`判断的是“当前没有任何地址”，并不是“当前没有默认地址”。删除默认但保留其他普通地址后，再新增仍为普通地址。删完全部后，下一次新增才重新自动默认。

### 5.3 修改内容

`addressDetails`共用于创建和修改，将五个文本字段strip，手机号原样保存已通过校验的值。修改补上路径id，由`updateDetails`用id和userId双条件更新。

更新SQL不包含`is_default`，所以内容修改保留默认标记。0行表示没有本人目标，抛404；成功后重新查本人地址，把数据库中保留的默认标记一起返回。提交完全相同的合法内容仍正常200，已在当前MySQL连接配置下验证。

### 5.4 删除

先锁用户，再按id与userId删除。0行抛404；成功204。不查询另一个地址来递补默认，所以删除默认后可以没有默认地址。这是已确认的业务规则。

### 5.5 设置默认

```text
锁用户 → 查本人目标，不存在立即404
      → 清除本人旧默认 → 标记本人目标 → 同一事务提交
```

先检查归属再清旧，避免传入别人的id时自己的默认地址被清除。`clearDefault`和`markDefault`在同一个事务里；设新SQL失败会回滚清旧操作，恢复原默认。

重复设置同一目标仍成功：先清为0，再设回1。两个目标同时竞争默认时依用户锁顺序执行，最终最多一个默认；不承诺网络先发出的请求一定获胜。

查询列表不拿写锁，单条SELECT读取其可见的已提交数据，不会读到另一个事务尚未提交的“清旧后未设新”状态。

## 6. Mapper与SQL：每一次操作都有归属范围

[AddressMapper.java](/D:/codex/Java/limited-trade-platform/src/main/java/com/elaina/limitedtrade/address/AddressMapper.java)：

```java
package com.elaina.limitedtrade.address;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AddressMapper {
    List<UserAddress> findUserAddresses(long userId);
    long countUserAddresses(long userId);
    UserAddress findOwnedAddress(@Param("id") long id, @Param("userId") long userId);
    int insertAddress(UserAddress address);
    int updateDetails(UserAddress address);
    int deleteOwnedAddress(@Param("id") long id, @Param("userId") long userId);
    int clearDefault(long userId);
    int markDefault(@Param("id") long id, @Param("userId") long userId);
}
```

[AddressMapper.xml](/D:/codex/Java/limited-trade-platform/src/main/resources/mapper/AddressMapper.xml)：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.elaina.limitedtrade.address.AddressMapper">
    <resultMap id="addressMap" type="com.elaina.limitedtrade.address.UserAddress">
        <id property="id" column="id"/>
        <result property="userId" column="user_id"/>
        <result property="recipientName" column="recipient_name"/>
        <result property="phone" column="phone"/>
        <result property="province" column="province"/>
        <result property="city" column="city"/>
        <result property="district" column="district"/>
        <result property="detailAddress" column="detail_address"/>
        <result property="defaultAddress" column="is_default"/>
    </resultMap>
    <sql id="addressColumns">id,user_id,recipient_name,phone,province,city,district,detail_address,is_default</sql>
    <select id="findUserAddresses" resultMap="addressMap">
        SELECT <include refid="addressColumns"/> FROM user_address
        WHERE user_id = #{userId} ORDER BY is_default DESC,id ASC
    </select>
    <select id="countUserAddresses" resultType="long">
        SELECT COUNT(*) FROM user_address WHERE user_id = #{userId}
    </select>
    <select id="findOwnedAddress" resultMap="addressMap">
        SELECT <include refid="addressColumns"/> FROM user_address WHERE id = #{id} AND user_id = #{userId}
    </select>
    <insert id="insertAddress" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO user_address(user_id,recipient_name,phone,province,city,district,detail_address,is_default)
        VALUES (#{userId},#{recipientName},#{phone},#{province},#{city},#{district},#{detailAddress},#{defaultAddress})
    </insert>
    <update id="updateDetails">
        UPDATE user_address SET recipient_name=#{recipientName},phone=#{phone},province=#{province},
            city=#{city},district=#{district},detail_address=#{detailAddress}
        WHERE id=#{id} AND user_id=#{userId}
    </update>
    <delete id="deleteOwnedAddress">
        DELETE FROM user_address WHERE id=#{id} AND user_id=#{userId}
    </delete>
    <update id="clearDefault">
        UPDATE user_address SET is_default=0 WHERE user_id=#{userId} AND is_default=1
    </update>
    <update id="markDefault">
        UPDATE user_address SET is_default=1 WHERE id=#{id} AND user_id=#{userId}
    </update>
</mapper>
```

接口方法名与XML的id对应；namespace指向AddressMapper。两个参数的方法显式`@Param`，XML里的`#{id}`、`#{userId}`分别绑定这两个参数，使用预编译参数而非拼接输入。

`resultMap`明确将`is_default`映射为`defaultAddress`，因为这不是简单的下划线转驼峰命名。其他字段也显式映射，查询列通过`addressColumns`复用。

`insertAddress`开启`useGeneratedKeys`，`keyProperty="id"`让MyBatis把自增编号写回传入实体。内容UPDATE明确只改六个内容列。查单条、修改、删除、设目标默认都同时限定`id`和`user_id`；清默认只影响该用户且当前为默认的行。

## 7. 一次具体请求

```http
POST /api/addresses
Content-Type: application/json
```

```json
{
  "recipientName": " 张三 ",
  "phone": "13800138000",
  "province": "浙江省",
  "city": "杭州市",
  "district": "西湖区",
  "detailAddress": "学院路1号"
}
```

请求需携带有效登录Session及CSRF。Controller校验并取得登录用户编号；Service开事务锁用户行，将姓名裁剪为“张三”，判断是否首条，插入回填地址id；响应201，包含六个内容字段、生成的id和defaultAddress。

接着`PUT /api/addresses/{id}/default`不提交正文，按前面流程切换；其他用户使用这个id会得到404。修改地址簿不会修改原订单，后续结算阶段还需要在下单时复制地址快照。

## 8. 验证记录

地址专项35项通过，包含真实MySQL并发、受控SQL异常回滚、登录持久化，以及MockMvc输入/权限验证。完整离线package338项通过，0失败、0错误、0跳过，BUILD SUCCESS。独立只读复核未发现重要问题。开发原七表公开字段快照一致，新地址表为空；测试八表和自有触发器清理，随机Redis/MQ测试资源无残留，JAR边界检查通过。

接口结果来自MockMvc，数据库操作使用真实MySQL；没有新增真实HTTP监听端口验证。全量回归另包含既有Redis和RabbitMQ集成，不代表吞吐或生产环境性能。

没有新增依赖、下载或部署；没有提交、推送或合并。没有接入多商品结算。学习按本节新增源码继续，不展示测试源码。
