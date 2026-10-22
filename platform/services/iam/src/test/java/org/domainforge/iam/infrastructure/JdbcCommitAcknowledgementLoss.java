package org.domainforge.iam.infrastructure;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import javax.sql.DataSource;
import org.domainforge.iam.infrastructure.persistence.operation.ReceiptMapper;
import org.domainforge.iam.infrastructure.persistence.operation.SqlCommandReceipts;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** 真实JDBC提交成功后丢失确认，让原生JdbcTransactionManager负责SQLException翻译。 */
final class JdbcCommitAcknowledgementLoss extends DelegatingDataSource {
  JdbcCommitAcknowledgementLoss(DataSource delegate) {
    super(delegate);
  }

  @Override
  public Connection getConnection() throws SQLException {
    Connection actual = super.getConnection();
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              try {
                Object result = method.invoke(actual, arguments);
                if (method.getName().equals("commit"))
                  throw new SQLRecoverableException(
                      "controlled committed acknowledgement loss", "08006");
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  /** Mapper和被测管理器使用同一个DataSource资源键，不能让SQL另开连接自动提交。 */
  SqlCommandReceipts receipts() throws Exception {
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(this);
    factory.setMapperLocations(new ClassPathResource("mapper/operation/ReceiptMapper.xml"));
    var configuration = new org.apache.ibatis.session.Configuration();
    configuration.setMapUnderscoreToCamelCase(true);
    factory.setConfiguration(configuration);
    factory.afterPropertiesSet();
    return new SqlCommandReceipts(
        new SqlSessionTemplate(factory.getObject()).getMapper(ReceiptMapper.class));
  }
}
