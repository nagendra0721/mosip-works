package io.mosip.idrepository.anonymousprofile.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSourceConfig {

	@Bean(name = "sourceDataSource")
	@ConfigurationProperties(prefix = "rebuild.source.datasource")
	public DataSource sourceDataSource() {
		return DataSourceBuilder.create().build();
	}

	@Bean(name = "targetDataSource")
	@ConfigurationProperties(prefix = "rebuild.target.datasource")
	public DataSource targetDataSource() {
		return DataSourceBuilder.create().build();
	}
}
