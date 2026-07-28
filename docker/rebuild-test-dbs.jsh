// 重建集成测试库（历史经验 3：非幂等，复跑前重建）
// 用法：jshell --class-path <mysql-connector.jar> rebuild-test-dbs.jsh
import java.sql.*;

var url = "jdbc:mysql://192.168.50.10:3306?useSSL=false&allowPublicKeyRetrieval=true";
var dbs = new String[]{"test", "test_message", "test_tenant", "test_tenant_s1", "test_tenant_s2", "test_tenant_d1", "test_tenant_d2"};
try (var conn = DriverManager.getConnection(url, "root", "mysql_PBM2cc");
     var st = conn.createStatement()) {
    for (String db : dbs) {
        st.executeUpdate("DROP DATABASE IF EXISTS " + db);
        st.executeUpdate("CREATE DATABASE " + db + " DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci");
        System.out.println("rebuilt: " + db);
    }
}
/exit
