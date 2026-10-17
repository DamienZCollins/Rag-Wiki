package com.ragwiki.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配：mapper 扫描与分页插件。
 * 驼峰映射在 config/application.yml 的 mybatis-plus.configuration 中配置。
 */
@Configuration
@MapperScan({
        "com.ragwiki.agent.mapper",
        "com.ragwiki.wiki.mapper",
        "com.ragwiki.rag.mapper",
        "com.ragwiki.eval.mapper"
})
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
