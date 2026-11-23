import com.sun.management.OperatingSystemMXBean;
import com.sun.tools.attach.VirtualMachine;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

// 在独立的采样进程中读取目标 JVM 的本机 JMX 指标。
class JvmSample {
    public static void main(String[] args) throws Exception {
        var vm = VirtualMachine.attach(args[0]);
        String address;
        try {
            address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        try (var connection = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            var server = connection.getMBeanServerConnection();
            var os = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBean.class);
            var memory = ManagementFactory.newPlatformMXBeanProxy(server, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
            var collectors = ManagementFactory.getPlatformMXBeans(server, GarbageCollectorMXBean.class);
            var pool = new ObjectName("com.zaxxer.hikari:type=Pool (HikariPool-1)");
            while (true) {
                var heap = memory.getHeapMemoryUsage();
                long collections = collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
                long collectionMs = collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
                System.out.printf(
                        "{\"timeMs\":%d,\"cpuNs\":%d,\"heapBytes\":%d,\"heapMaxBytes\":%d,\"gcCount\":%d,\"gcMs\":%d,"
                                + "\"poolActive\":%d,\"poolTotal\":%d,\"poolWaiting\":%d}%n",
                        System.currentTimeMillis(), os.getProcessCpuTime(), heap.getUsed(), heap.getMax(), collections, collectionMs,
                        server.getAttribute(pool, "ActiveConnections"), server.getAttribute(pool, "TotalConnections"),
                        server.getAttribute(pool, "ThreadsAwaitingConnection"));
                Thread.sleep(250);
            }
        }
    }
}
