package io.iohk.atala.iam.authorization.keycloak.admin

import io.iohk.atala.agent.server.config.AppConfig
import org.keycloak.admin.client.Keycloak
import zio.{RLayer, Task, TaskLayer, URLayer, ZIO, ZLayer}

import scala.util.{Failure, Try}

type KeycloakAdmin = Keycloak

// The following arguments are available:
// serverUrl: String,
// realm: String,
// username: String,
// password: String,
// clientId: String,
// clientSecret: String,
// sslContext: SSLContext,
// customJacksonProvider: AnyRef, - should be skipped
// disableTrustManager: Boolean, - false by default
// authToken: String, - can be skipped
// scope: String - can be skipped
// TODO: Ssl context is not supported yet
case class KeycloakAdminConfig(
    serverUrl: String,
    realm: String,
    username: String,
    password: String,
    clientId: String,
    clientSecret: Option[String],
    authToken: Option[String],
    scope: Option[String]
) {
  def isHttps: Boolean = serverUrl.startsWith("https")
}

object KeycloakAdminConfig {
  val layer: URLayer[AppConfig, KeycloakAdminConfig] =
    ZLayer.fromFunction((appConfig: AppConfig) => appConfig.keycloakAdmin)
}

object KeycloakAdmin {

  def apply(config: KeycloakAdminConfig): Task[KeycloakAdmin] = {
    if (config.isHttps)
      ZIO.fail(new Exception("Ssl is not supported yet"))
    else
      ZIO.fromTry[KeycloakAdmin](
        Try[KeycloakAdmin](
          Keycloak.getInstance(
            config.serverUrl,
            config.realm,
            config.username,
            config.password,
            config.clientId,
            config.clientSecret.orNull
          )
        )
      )
  }

  val layer: RLayer[KeycloakAdminConfig, KeycloakAdmin] =
    ZLayer.fromZIO(ZIO.service[KeycloakAdminConfig].flatMap(config => apply(config)))
}
