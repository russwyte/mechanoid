package mechanoid
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import saferis.ConnectionProvider
import zio.*

import javax.sql.DataSource

final case class ContainerConfig(
    initScriptPath: String = "init.sql",
    imageName: String = s"${PostgreSQLContainer.IMAGE}:latest",
)
object ContainerConfig:
  val default = ZLayer.succeed(ContainerConfig())

final case class PostgresTestContainer(
    config: ContainerConfig
):
  val postgres: PostgreSQLContainer[?] =
    val container: PostgreSQLContainer[?] =
      new PostgreSQLContainer(config.imageName)
        // Disable password checks
        .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
    container.withInitScript(config.initScriptPath)
    container

  def start: PostgresTestContainer =
    postgres.start()
    this

  val stop: UIO[Unit] =
    ZIO.succeed:
      postgres.stop()
end PostgresTestContainer

object PostgresTestContainer:
  val base  = ZLayer.derive[PostgresTestContainer]
  val layer = base >>> ZLayer.scoped:
    ZIO.acquireRelease(ZIO.service[PostgresTestContainer].map(_.start))(_.stop)
  val default = ContainerConfig.default >>> layer

  final case class DataSourceProvider(container: PostgresTestContainer):
    import container.postgres

    def dataSource: DataSource =
      val ds = PGSimpleDataSource()
      ds.setURL(postgres.getJdbcUrl())
      ds.setUser(postgres.getUsername())
      ds.setPassword(postgres.getPassword())
      ds
  end DataSourceProvider

  object DataSourceProvider:
    def makeDataSource(container: PostgresTestContainer): DataSource =
      val ds = PGSimpleDataSource()
      ds.setURL(container.postgres.getJdbcUrl())
      ds.setUser(container.postgres.getUsername())
      ds.setPassword(container.postgres.getPassword())
      ds

    def makeConnectionProvider(container: PostgresTestContainer): ConnectionProvider =
      ConnectionProvider.FromDataSource(makeDataSource(container))

    val datasource: URLayer[PostgresTestContainer, DataSource] =
      ZLayer.derive[DataSourceProvider].map(env => ZEnvironment(env.get.dataSource))

    val provider =
      ZLayer.fromFunction(makeConnectionProvider)

    val default =
      PostgresTestContainer.default >>> provider
  end DataSourceProvider
end PostgresTestContainer
