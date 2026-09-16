package com.feelm.backend.domain.social;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** API-032의 실제 JDBC query 수를 현재 요청 thread에서만 기록한다. */
final class PublicReviewQueryRecorder {

    private final ThreadLocal<List<String>> statements = new ThreadLocal<>();
    private final AtomicReference<QueryPause> nextPause = new AtomicReference<>();

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

    QueryPause pauseBefore(String sqlFragment) {
        return configurePause(sqlFragment, false);
    }

    QueryPause pauseAfter(String sqlFragment) {
        return configurePause(sqlFragment, true);
    }

    private QueryPause configurePause(String sqlFragment, boolean afterExecution) {
        QueryPause pause = new QueryPause(sqlFragment, afterExecution);
        if (!nextPause.compareAndSet(null, pause)) {
            throw new IllegalStateException("A query pause is already configured");
        }
        return pause;
    }

    void clearPause() {
        QueryPause pause = nextPause.getAndSet(null);
        if (pause != null) {
            pause.release();
        }
    }

    private void beforeExecute(String sql) throws SQLException {
        QueryPause pause = nextPause.get();
        if (pause != null
                && !pause.afterExecution
                && pause.matches(sql)
                && nextPause.compareAndSet(pause, null)) {
            waitForRelease(pause);
        }
    }

    private void afterExecute(String sql) throws SQLException {
        QueryPause pause = nextPause.get();
        if (pause != null
                && pause.afterExecution
                && pause.matches(sql)
                && nextPause.compareAndSet(pause, null)) {
            waitForRelease(pause);
        }
    }

    private static void waitForRelease(QueryPause pause) throws SQLException {
        pause.reach();
        try {
            if (!pause.awaitRelease()) {
                throw new SQLException("Timed out waiting to resume the paused test query");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SQLException(
                    "Interrupted while waiting to resume the paused test query", exception);
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
                    String sql = null;
                    if (method.getName().startsWith("execute")) {
                        if (preparedSql != null) {
                            sql = preparedSql;
                        } else if (args != null
                                && args.length > 0
                                && args[0] instanceof String statementSql) {
                            sql = statementSql;
                        }
                        if (sql != null) {
                            beforeExecute(sql);
                            record(sql);
                        }
                    }
                    Object result = invoke(target, method, args);
                    if (sql != null) {
                        afterExecute(sql);
                    }
                    return result;
                });
    }

    static final class QueryPause {

        private final String normalizedFragment;
        private final boolean afterExecution;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private QueryPause(String sqlFragment, boolean afterExecution) {
            normalizedFragment = sqlFragment.toUpperCase(Locale.ROOT);
            this.afterExecution = afterExecution;
        }

        private boolean matches(String sql) {
            return sql.toUpperCase(Locale.ROOT).contains(normalizedFragment);
        }

        private void reach() {
            reached.countDown();
        }

        void awaitReached() throws InterruptedException {
            if (!reached.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The expected query was not reached");
            }
        }

        private boolean awaitRelease() throws InterruptedException {
            return released.await(30, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }
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
        static PublicReviewQueryRecorder publicReviewQueryRecorder() {
            return new PublicReviewQueryRecorder();
        }

        @Bean
        static BeanPostProcessor recordPublicReviewQueries(PublicReviewQueryRecorder recorder) {
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

        private final PublicReviewQueryRecorder recorder;

        private RecordingDataSource(DataSource source, PublicReviewQueryRecorder recorder) {
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
