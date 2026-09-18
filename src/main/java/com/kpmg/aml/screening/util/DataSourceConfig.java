/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.util;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 *
 * @author user
 */
@Configuration
public class DataSourceConfig {
    
    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.postgres")
    public DataSource postgresDataSource(){
        return DataSourceBuilder.create().build();
    }
    
    @Bean("oracleDataSource")
    @ConfigurationProperties("spring.datasource.oracle")
    public DataSource oracleDataSource(){
        return DataSourceBuilder.create().build();
    }
    
    @Bean("oracleJdbcTemplate")
    public JdbcTemplate oracleJdbcTemplate(@Qualifier("oracleDataSource") DataSource dataSource){
        return new JdbcTemplate(dataSource);
    }
}
