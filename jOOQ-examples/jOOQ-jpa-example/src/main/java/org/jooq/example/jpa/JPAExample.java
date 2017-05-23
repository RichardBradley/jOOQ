/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: http://www.jooq.org/licenses
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.example.jpa;

import static org.jooq.example.jpa.jooq.Tables.*;
import static org.jooq.impl.DSL.count;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.EnumSet;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.example.jpa.entity.Aa;
import org.jooq.example.jpa.entity.Actor;
import org.jooq.example.jpa.entity.Bb;
import org.jooq.example.jpa.entity.Cc;
import org.jooq.example.jpa.entity.Dd;
import org.jooq.example.jpa.entity.Film;
import org.jooq.example.jpa.entity.Language;
import org.jooq.example.jpa.jooq.Tables;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListener;

import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * @author Lukas Eder
 */
class JPAExample {

    private static void run(EntityManager em, DSLContext ctx) {

        // Set up database
        // ---------------
        Aa one = new Aa("one");
        Bb two = new Bb("two", one);
        Cc three = new Cc("three", two);
        Dd four = new Dd("four", two);

        em.persist(one);
        em.persist(two);
        em.persist(three);
        em.persist(four);

        // Flush your changes to the database to be sure that jOOQ can pick them up below
        // ------------------------------------------------------------------------------
        em.flush();

        System.out.println(
            ctx.select(
                    AA.TEXT,
                    BB.TEXT,
                    CC.TEXT,
                    DD.TEXT)
               .from(AA)
                // Branching chains like Aa -> Bb -> Cc, Bb -> Dd cannot be handled by "onKey()"
               .join(BB).onKey()
               .join(CC).onKey()
               .join(DD).onKey()
               .fetch()
        );
    }

    // Just ignore that enterprisish bootstrapping madness down there. The beef of the example is above this line
    // ----------------------------------------------------------------------------------------------------------

    @SuppressWarnings("serial")
    public static void main(String[] args) throws Exception {
        Connection connection = null;
        EntityManagerFactory emf = null;
        EntityManager em = null;

        try {

            // Bootstrapping JDBC:
            Class.forName("org.h2.Driver");
            connection = DriverManager.getConnection("jdbc:h2:mem:jooq-jpa-example", "sa", "");
            final Connection c = connection;

            // Creating an in-memory H2 database from our entities
            MetadataSources metadata = new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                    .applySetting("javax.persistence.schema-generation-connection", connection)
                    .applySetting("javax.persistence.create-database-schemas", true)

                    // [#5607] JPADatabase causes warnings - This prevents
                    // them
                    .applySetting(AvailableSettings.CONNECTION_PROVIDER, new ConnectionProvider() {
                        @SuppressWarnings("rawtypes")
                        @Override
                        public boolean isUnwrappableAs(Class unwrapType) {
                            return false;
                        }

                        @Override
                        public <T> T unwrap(Class<T> unwrapType) {
                            return null;
                        }

                        @Override
                        public Connection getConnection() {
                            return c;
                        }

                        @Override
                        public void closeConnection(Connection conn) throws SQLException {}

                        @Override
                        public boolean supportsAggressiveRelease() {
                            return true;
                        }
                    })
                    .build());

            metadata.addAnnotatedClass(Actor.class);
            metadata.addAnnotatedClass(Film.class);
            metadata.addAnnotatedClass(Language.class);

            metadata.addAnnotatedClass(Aa.class);
            metadata.addAnnotatedClass(Bb.class);
            metadata.addAnnotatedClass(Cc.class);
            metadata.addAnnotatedClass(Dd.class);

            SchemaExport export = new SchemaExport();
            export.create(EnumSet.of(TargetType.DATABASE), metadata.buildMetadata());

            // Setting up an EntityManager using Spring (much easier than out-of-the-box Hibernate)
            LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
            HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
            adapter.setDatabasePlatform(SQLDialect.H2.thirdParty().hibernateDialect());
            bean.setDataSource(new SingleConnectionDataSource(connection, true));
            bean.setPackagesToScan("org.jooq.example.jpa.entity");
            bean.setJpaVendorAdapter(adapter);
            bean.setPersistenceUnitName("test");
            bean.setPersistenceProviderClass(HibernatePersistenceProvider.class);
            bean.afterPropertiesSet();

            emf = bean.getObject();
            em = emf.createEntityManager();

            final EntityManager e = em;

            // Run some Hibernate / jOOQ logic inside of a transaction
            em.getTransaction().begin();
            run(
                em,
                DSL.using(new DefaultConfiguration()
                    .set(connection)
                    .set(new DefaultExecuteListener() {
                        @Override
                        public void start(ExecuteContext ctx) {
                            // Flush all changes from the EntityManager to the database for them to be visible in jOOQ
                            e.flush();
                            super.start(ctx);
                        }
                    })
            ));
            em.getTransaction().commit();
        }
        finally {
            if (em != null)
                em.close();

            if (emf != null)
                emf.close();

            if (connection != null)
                connection.close();
        }
    }
}
