package org.domainforge.persistence.autoconfigure;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.transaction.autoconfigure.TransactionProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;

/** 仅验证明确命名的本地资源，不把无关管理器误当成第二个受管数据库。 */
class LocalResourceBindingTest {
  @Test
  void explicitlyBoundResourcesAllowUnrelatedManagerWithoutChangingIt() {
    try (var context = context()) {
      var unrelated = new DataSourceTransactionManager(new DriverManagerDataSource());
      context.registerBean("unrelatedManager", DataSourceTransactionManager.class, () -> unrelated);
      context.refresh();
      assertEquals(
          10,
          context
              .getBean("transactionManager", DataSourceTransactionManager.class)
              .getDefaultTimeout());
      assertEquals(TransactionDefinition.TIMEOUT_DEFAULT, unrelated.getDefaultTimeout());
    }
  }

  @Test
  void unrelatedResourcesDoNotReplaceExplicitBinding() {
    try (var context = context()) {
      var other = new DriverManagerDataSource();
      context.registerBean("otherSource", DriverManagerDataSource.class, () -> other);
      context.registerBean("otherSessions", SqlSessionFactory.class, () -> session(other));
      context.registerBean(
          "otherMigration", Flyway.class, () -> Flyway.configure().dataSource(other).load());
      assertDoesNotThrow(context::refresh);
    }
  }

  @Test
  void missingBindingDoesNotFallBackToSingleOtherManager() {
    try (var context = context()) {
      context.removeBeanDefinition("transactionManager");
      context.registerBean(
          "otherManager",
          DataSourceTransactionManager.class,
          () -> new DataSourceTransactionManager());
      assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
    }
  }

  @Test
  void wrongSessionOrMigrationResourceIsRejected() {
    for (String name : java.util.List.of("sqlSessionFactory", "flyway")) {
      try (var context = context()) {
        var other = new DriverManagerDataSource();
        context.removeBeanDefinition(name);
        if (name.equals("sqlSessionFactory")) {
          context.registerBean(name, SqlSessionFactory.class, () -> session(other));
        } else {
          context.registerBean(
              name, Flyway.class, () -> Flyway.configure().dataSource(other).load());
        }
        assertThrows(IllegalStateException.class, context::refresh, name);
      }
    }
  }

  static AnnotationConfigApplicationContext context() {
    var context = new AnnotationConfigApplicationContext();
    var source = new DriverManagerDataSource();
    context.registerBean("dataSource", DriverManagerDataSource.class, () -> source);
    context.registerBean(
        "transactionManager",
        DataSourceTransactionManager.class,
        () -> new DataSourceTransactionManager(source));
    context.registerBean("sqlSessionFactory", SqlSessionFactory.class, () -> session(source));
    context.registerBean(
        "flyway", Flyway.class, () -> Flyway.configure().dataSource(source).load());
    context.registerBean(TransactionProperties.class);
    context.register(DomainForgePersistenceConfiguration.class);
    return context;
  }

  static SqlSessionFactory session(javax.sql.DataSource source) {
    return new SqlSessionFactoryBuilder()
        .build(
            new org.apache.ibatis.session.Configuration(
                new Environment("local-binding", new JdbcTransactionFactory(), source)));
  }
}
