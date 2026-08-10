<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.pivotos</groupId>
        <artifactId>pivotos-plugins</artifactId>
        <version>${projectVersion}</version>
    </parent>

    <artifactId>pivotos-plugin-${pluginName}</artifactId>
    <name>pivotos-plugin-${pluginName}</name>
    <description>PivotOS ${displayName}插件实现层：${moduleDesc}——AI Coding 骨架生成</description>

    <dependencies>
        <!-- 本插件契约 -->
        <dependency>
            <groupId>com.pivotos</groupId>
            <artifactId>pivotos-plugin-${pluginName}-api</artifactId>
        </dependency>

        <!-- 底座 Starter（按需增删，跨 Plugin 只许依赖 -api 包） -->
        <dependency>
            <groupId>com.pivotos</groupId>
            <artifactId>pivotos-starter-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.pivotos</groupId>
            <artifactId>pivotos-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.pivotos</groupId>
            <artifactId>pivotos-starter-mybatis</artifactId>
        </dependency>
        <dependency>
            <groupId>com.pivotos</groupId>
            <artifactId>pivotos-starter-auth</artifactId>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
