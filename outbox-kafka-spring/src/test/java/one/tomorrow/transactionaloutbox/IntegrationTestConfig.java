package one.tomorrow.transactionaloutbox;

import org.apache.commons.dbcp.BasicDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.test.FlywayHelperFactory;
import org.hibernate.SessionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.hibernate5.HibernateTransactionManager;
import org.springframework.orm.hibernate5.LocalSessionFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Properties;

@Configuration
@EnableTransactionManagement
public class IntegrationTestConfig {

    public static final Duration DEFAULT_OUTBOX_LOCK_TIMEOUT = Duration.ofMillis(200);

    @Bean
    public LocalSessionFactoryBean sessionFactory(DataSource dataSource) {
        LocalSessionFactoryBean sessionFactory = new LocalSessionFactoryBean();

        sessionFactory.setDataSource(dataSource);
        sessionFactory.setPackagesToScan(getClass().getPackage().getName());
        sessionFactory.setHibernateProperties(getHibernateProperties());

        return sessionFactory;
    }

    @Bean
    public PlatformTransactionManager transactionManager(SessionFactory sessionFactory) {
        return new HibernateTransactionManager(sessionFactory);
    }

    private Properties getHibernateProperties() {
        Properties properties = new Properties();
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.show_sql", false);
        return properties;
    }

    @Bean
    public DataSource dataSource() {
        BasicDataSource dataSource = new BasicDataSource();

        dataSource.setDriverClassName("org.testcontainers.jdbc.ContainerDatabaseDriver");
        dataSource.setUrl("jdbc:tc:postgresql:10.8://localhost/test");

        return dataSource;
    }

    @Bean
    public Flyway flywayFactory(ClassicConfiguration configuration) {
        FlywayHelperFactory factory = new FlywayHelperFactory();

        factory.setFlywayConfiguration(configuration);
        factory.setFlywayProperties(new Properties());

        return factory.createFlyway();
    }

    @Bean
    public ClassicConfiguration flywayConfiguration(DataSource dataSource) {
        ClassicConfiguration configuration = new ClassicConfiguration();

        configuration.setDataSource(dataSource);
        configuration.setLocationsAsStrings("classpath:/db/migration");

        return configuration;
    }

}
