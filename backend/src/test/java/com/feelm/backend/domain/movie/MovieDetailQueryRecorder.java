package com.feelm.backend.domain.movie;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** JPA와 직접 JDBC의 실제 SQL 실행을 모두 센다. 바인딩 값은 저장하지 않는다. */
final class MovieDetailQueryRecorder {

    private final ThreadLocal<List<String>> statements = new ThreadLocal<>();

    void start() {
        statements.set(new ArrayList<>());
    }

    List<String> stop() {
        List<String> recorded = statements.get();
        statements.remove();
        return recorded == null ? List.of() : List.copyOf(recorded);
    }

    private void record(String sql) {
        List<String> recorded = statements.get();
        if (recorded != null) {
            recorded.add(sql);
        }
    }

    private Connection connection(Connection target) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(target, method, args);
                            if (result instanceof PreparedStatement prepared) {
                                return statement(
                                        prepared, (String) args[0], PreparedStatement.class);
                            }
                            if (result instanceof Statement statement) {
                                return statement(statement, null, Statement.class);
                            }
                            return result;
                        });
    }

    private Object statement(Statement target, String preparedSql, Class<?> type) {
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("execute")) {
                        if (preparedSql != null) {
                            record(preparedSql);
                        } else if (args != null
                                && args.length > 0
                                && args[0] instanceof String sql) {
                            record(sql);
                        }
                    }
                    return invoke(target, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        static MovieDetailQueryRecorder movieDetailQueryRecorder() {
            return new MovieDetailQueryRecorder();
        }

        @Bean
        static BeanPostProcessor recordMovieDetailQueries(MovieDetailQueryRecorder recorder) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource source) {
                        return new RecordingDataSource(source, recorder);
                    }
                    return bean;
                }
            };
        }
    }

    private static final class RecordingDataSource extends DelegatingDataSource
            implements AutoCloseable {

        private final MovieDetailQueryRecorder recorder;

        private RecordingDataSource(DataSource source, MovieDetailQueryRecorder recorder) {
            super(source);
            this.recorder = recorder;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return recorder.connection(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return recorder.connection(super.getConnection(username, password));
        }

        @Override
        public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
}
