package com.seonbi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Entity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Flyway 마이그레이션(V1, V2...)을 실제 PostgreSQL에서 검증한다. 스프링 컨텍스트 없이 DB마다 새로 만들어서 쓰므로 서로 영향이 없다.
 *
 * <ul>
 *   <li>빈 DB에 모든 테이블이 만들어지고 다시 실행해도 아무 일도 일어나지 않는다
 *   <li>Hibernate가 Entity로 만드는 스키마(지금까지 운영 DB를 만든 방식)와 컬럼·제약·인덱스 이름까지 같다
 *   <li>PK·FK·UNIQUE·NOT NULL·CHECK·CASCADE가 실제로 동작한다
 *   <li>이미 테이블이 있는 DB는 baseline 없이는 막히고, baseline 후에는 데이터를 건드리지 않고 이어진다
 * </ul>
 */
class FlywayMigrationTest {

  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.4");

  static {
    PG.start();
  }

  /** 서버 안에 테스트마다 새 데이터베이스를 만든다. */
  private static String newDatabase() throws SQLException {
    String name = "t_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c =
            DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Statement s = c.createStatement()) {
      s.execute("create database " + name);
    }
    return name;
  }

  private static String url(String db) {
    return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
  }

  private static Connection connect(String db) throws SQLException {
    return DriverManager.getConnection(url(db), PG.getUsername(), PG.getPassword());
  }

  private static Flyway flyway(String db) {
    return Flyway.configure()
        .dataSource(url(db), PG.getUsername(), PG.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .load();
  }

  private static List<String> tables(String db) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = connect(db);
        Statement s = c.createStatement();
        ResultSet r =
            s.executeQuery(
                "select tablename from pg_tables where schemaname='public' order by tablename")) {
      while (r.next()) out.add(r.getString(1));
    }
    return out;
  }

  /** scripts/schema-fingerprint.sql 로 컬럼·제약·인덱스 지문을 뽑는다. 운영 DB를 비교할 때 쓰는 것과 같은 SQL이다. */
  private static List<String> fingerprint(String db) throws Exception {
    String sql = Files.readString(Path.of("..", "scripts", "schema-fingerprint.sql"));
    List<String> out = new ArrayList<>();
    try (Connection c = connect(db);
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      while (r.next()) out.add(r.getString(1));
    }
    return out;
  }

  /** Hibernate가 Entity만 보고 만드는 스키마. ddl-auto 가 만들어 오던 것과 같다. */
  private static void hibernateCreate(String db) {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
    StandardServiceRegistry registry =
        new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", url(db))
            .applySetting("hibernate.connection.username", PG.getUsername())
            .applySetting("hibernate.connection.password", PG.getPassword())
            .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
            .applySetting("hibernate.hbm2ddl.auto", "create") // SessionFactory 를 만들 때 테이블을 만든다
            .build();
    try {
      MetadataSources sources = new MetadataSources(registry);
      scanner.findCandidateComponents("com.seonbi.domain").stream()
          .map(d -> d.getBeanClassName())
          .forEach(
              n -> {
                try {
                  sources.addAnnotatedClass(Class.forName(n));
                } catch (ClassNotFoundException e) {
                  throw new IllegalStateException(e);
                }
              });
      Metadata metadata = sources.buildMetadata();
      metadata.buildSessionFactory().close();
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  private static void exec(String db, String sql) throws SQLException {
    try (Connection c = connect(db);
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static long count(String db, String sql) throws SQLException {
    try (Connection c = connect(db);
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      r.next();
      return r.getLong(1);
    }
  }

  private static final List<String> APP_TABLES =
      List.of(
          "ai_usage_logs",
          "analysis_results",
          "answers",
          "condition_satisfactions",
          "conditions",
          "criteria",
          "decisions",
          "estimate_snapshots",
          "final_choices",
          "free_text_inputs",
          "option_values",
          "options",
          "questions");

  @Test
  void 빈_DB에_모든_테이블이_만들어지고_다시_실행해도_아무것도_바뀌지_않는다() throws Exception {
    String db = newDatabase();
    var first = flyway(db).migrate();
    assertThat(first.migrationsExecuted).isEqualTo(2);
    List<String> expected = new ArrayList<>(APP_TABLES);
    expected.add("flyway_schema_history");
    expected.sort(null);
    assertThat(tables(db)).isEqualTo(expected);

    var again = flyway(db).migrate(); // 재실행: 이미 적용된 것은 건너뛴다
    assertThat(again.migrationsExecuted).isZero();
    flyway(db).validate(); // 적용한 파일이 그대로인지(체크섬) 확인
    assertThat(count(db, "select count(*) from flyway_schema_history where success")).isEqualTo(2);
  }

  @Test
  void Flyway가_만든_스키마는_Hibernate가_Entity로_만드는_스키마와_이름까지_같다() throws Exception {
    String viaFlyway = newDatabase(), viaHibernate = newDatabase();
    flyway(viaFlyway).migrate();
    hibernateCreate(viaHibernate);
    assertThat(fingerprint(viaFlyway)).isNotEmpty().isEqualTo(fingerprint(viaHibernate));
  }

  @Test
  void 제약조건이_실제로_동작한다() throws Exception {
    String db = newDatabase();
    flyway(db).migrate();
    String d1 = UUID.randomUUID().toString(), client = UUID.randomUUID().toString();
    String decision =
        "insert into decisions(id,client_id,title,category,status,rng_seed,version) values('%s','%s','제목','etc','DRAFT',1,1)";
    exec(db, decision.formatted(d1, client));

    // PK 중복
    assertThatThrownBy(() -> exec(db, decision.formatted(d1, client)))
        .hasMessageContaining("decisions_pkey");
    // NOT NULL
    assertThatThrownBy(
            () ->
                exec(
                    db,
                    "insert into decisions(id,client_id,category,status,rng_seed,version) values('%s','%s','etc','DRAFT',1,1)"
                        .formatted(UUID.randomUUID(), client)))
        .hasMessageContaining("title");
    // CHECK
    assertThatThrownBy(() -> exec(db, "update decisions set status='BAD' where id='" + d1 + "'"))
        .hasMessageContaining("decisions_check");
    // FK: 없는 결정에 선택지를 달 수 없다
    String option = "insert into options(id,decision_id,name,position) values('%s','%s','%s',0)";
    assertThatThrownBy(() -> exec(db, option.formatted(UUID.randomUUID(), UUID.randomUUID(), "A")))
        .hasMessageContaining("fkeot1a5y84rbu0bc4cdstwqvsv");
    // UNIQUE: 같은 결정 안에서 선택지 이름 중복 불가
    String o1 = UUID.randomUUID().toString();
    exec(db, option.formatted(o1, d1, "A"));
    assertThatThrownBy(() -> exec(db, option.formatted(UUID.randomUUID(), d1, "A")))
        .hasMessageContaining("options_decision_id_name_key");
    // 멱등 키: 같은 브라우저의 같은 setup_key 는 두 번 못 만들지만 NULL 은 여러 개 가능
    exec(db, "update decisions set setup_key='" + o1 + "' where id='" + d1 + "'");
    assertThatThrownBy(
            () ->
                exec(
                    db,
                    ("insert into decisions(id,client_id,title,category,status,rng_seed,version,setup_key) "
                            + "values('%s','%s','다른','etc','DRAFT',1,1,'%s')")
                        .formatted(UUID.randomUUID(), client, o1)))
        .hasMessageContaining("decisions_client_id_setup_key_key");
    exec(db, decision.formatted(UUID.randomUUID(), client));
    exec(db, decision.formatted(UUID.randomUUID(), client));

    // ON DELETE CASCADE: 결정을 지우면 하위 데이터도 함께 지워진다
    exec(db, "delete from decisions where id='" + d1 + "'");
    assertThat(count(db, "select count(*) from options where decision_id='" + d1 + "'")).isZero();
  }

  @Test
  void 이력이_없는_기존_DB에는_baseline_없이_적용되지_않고_데이터도_건드리지_않는다() throws Exception {
    String db = newDatabase();
    hibernateCreate(db); // 기존 운영 DB처럼 Hibernate가 만든 테이블이 이미 있다
    String id = UUID.randomUUID().toString();
    exec(
        db,
        "insert into decisions(id,client_id,title,category,status,rng_seed,version) values('%s','%s','기존 결정','etc','DRAFT',1,1)"
            .formatted(id, UUID.randomUUID()));

    assertThatThrownBy(() -> flyway(db).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("baseline");
    assertThat(count(db, "select count(*) from decisions")).isEqualTo(1); // 데이터 그대로
    assertThat(tables(db)).doesNotContain("flyway_schema_history");
  }

  @Test
  void 스키마가_같다고_확인한_기존_DB를_V1로_baseline하면_V2부터_적용되고_데이터는_그대로다() throws Exception {
    // 기존 DB 시나리오 1: setup_key 기능을 받기 전의 DB (V1 스키마와 같고 setup_key 만 없다)
    // V1까지만 적용된 DB를 "이력이 없는 기존 DB"로 삼는다
    String legacy = newDatabase();
    Flyway v1Only =
        Flyway.configure()
            .dataSource(url(legacy), PG.getUsername(), PG.getPassword())
            .locations("classpath:db/migration")
            .target("1")
            .load();
    v1Only.migrate();
    exec(legacy, "drop table flyway_schema_history"); // 이력이 없는 기존 DB처럼 만든다
    String id = UUID.randomUUID().toString();
    exec(
        legacy,
        "insert into decisions(id,client_id,title,category,status,rng_seed,version) values('%s','%s','기존 결정','etc','DRAFT',1,1)"
            .formatted(id, UUID.randomUUID()));

    Flyway.configure()
        .dataSource(url(legacy), PG.getUsername(), PG.getPassword())
        .locations("classpath:db/migration")
        .baselineVersion("1")
        .load()
        .baseline();
    var result = flyway(legacy).migrate();
    assertThat(result.migrationsExecuted).isEqualTo(1); // V2 만
    assertThat(count(legacy, "select count(*) from decisions where id='" + id + "'")).isEqualTo(1);
    assertThat(
            count(
                legacy,
                "select count(*) from information_schema.columns where table_name='decisions' and column_name='setup_key'"))
        .isEqualTo(1);

    // 기존 DB 시나리오 2: setup_key 를 ddl-auto=update 로 이미 받은 DB. V2 가 오류 없이 지나가고 제약이 중복되지 않는다
    String updated = newDatabase();
    hibernateCreate(updated);
    Flyway.configure()
        .dataSource(url(updated), PG.getUsername(), PG.getPassword())
        .locations("classpath:db/migration")
        .baselineVersion("1")
        .load()
        .baseline();
    assertThat(flyway(updated).migrate().migrationsExecuted).isEqualTo(1);
    String fresh = newDatabase();
    flyway(fresh).migrate();
    assertThat(fingerprint(updated)).isEqualTo(fingerprint(fresh));
  }
}
