package com.ccagent.infrastructure.di;

import com.ccagent.infrastructure.config.AppConfig;
import com.ccagent.infrastructure.constant.Constants;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public class CommonModule {

    private AppConfig config;
    private DataSource dataSource;

    public void init(AppConfig config) {
        this.config = config;

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:h2:file:" + Constants.DB_FILE);
        hikari.setUsername("sa");
        hikari.setPassword("");
        hikari.setMaximumPoolSize(5);
        hikari.setMinimumIdle(1);
        hikari.setIdleTimeout(30000);
        hikari.setConnectionTimeout(5000);
        hikari.setDriverClassName("org.h2.Driver");
        this.dataSource = new HikariDataSource(hikari);
    }

    public AppConfig getConfig() {
        return config;
    }

    public DataSource getDataSource() {
        return dataSource;
    }
}
