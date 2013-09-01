package net.pocrd.util;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@XmlRootElement
public class CommonConfig {
    public static final CommonConfig Instance;

    public static final boolean      isDebug = true;

    private CommonConfig() {}

    static {
        CommonConfig tmp = ConfigUtil.load("Common.config", CommonConfig.class);
        // 默认值设置
        if (tmp == null) {
            tmp = new CommonConfig();
            tmp.autogenPath = "E:\\software\\jd\\";
            tmp.tokenPwd = "KaKS8hro1Ljf7YXIK+iiag5ofiPmaucUqqfBTu7eVVI=";
            tmp.accessLoggerName = "net.pocrd.api.access";
            tmp.staticSignPwd = "pocrd@gmail.com";
            tmp.cacheVersion = "v1.0";
            tmp.cacheType = CacheDBType.Redis;
//            tmp.connectString = "jdbc:mysql://112.124.17.212:3306/test?useUnicode=true&amp;characterset=utf-8&user=gkq&password=gkq1990";
            tmp.jdbcPoolConfig=new JDBCPoolConfig();
            tmp.jdbcPoolConfig.setDriverClassName("com.mysql.jdbc.Driver");
            tmp.jdbcPoolConfig.setUserName("api");
            tmp.jdbcPoolConfig.setPassword("1q2w3e4r5t");
            tmp.jdbcPoolConfig.setJdbcUrl("jdbc:mysql://112.124.17.212:3306/test?useUnicode=true&amp;characterset=utf-8");
            tmp.jdbcPoolConfig.setMaxActive(100);
            tmp.jdbcPoolConfig.setInitialSize(10);
            tmp.jdbcPoolConfig.setMinEvictableIdleTimeMillis(30000);
            tmp.jdbcPoolConfig.setMinIdle(10);
            tmp.jdbcPoolConfig.setMaxIdle(100);
            tmp.jdbcPoolConfig.setMaxWait(10000);
            tmp.jdbcPoolConfig.setTestWhileIdle(true);//空闲连接测试
        }
        Instance = tmp;
        Instance.accessLogger = LogManager.getLogger(Instance.accessLoggerName);
        Instance.tokenHelper = new TokenHelper(Instance.tokenPwd);
    }

    public String      accessLoggerName;
    public String      tokenPwd;
    public String      staticSignPwd;
    public String      autogenPath;
    public String      cacheVersion;
    public CacheDBType cacheType;
//    public String      connectString;
    public JDBCPoolConfig jdbcPoolConfig;

    @XmlTransient
    public TokenHelper tokenHelper;

    @XmlTransient
    public Logger      accessLogger;

    /**
     * 缓存实现机制
     * 
     * @author guankaiqiang
     */
    public enum CacheDBType {
        Redis, Memcache
    }
}
