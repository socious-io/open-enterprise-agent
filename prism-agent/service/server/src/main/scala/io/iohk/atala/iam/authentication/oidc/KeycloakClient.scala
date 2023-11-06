package io.iohk.atala.iam.authentication.oidc

import io.iohk.atala.iam.authentication.AuthenticationError
import org.keycloak.authorization.client.AuthzClient
import org.keycloak.authorization.client.{Configuration => KeycloakAuthzConfig}
import org.keycloak.representations.idm.authorization.AuthorizationRequest
import zio.*
import zio.http.*
import zio.json.*

import scala.jdk.CollectionConverters.*

final case class TokenIntrospection(active: Boolean, sub: Option[String])

object TokenIntrospection {
  given JsonEncoder[TokenIntrospection] = JsonEncoder.derived
  given JsonDecoder[TokenIntrospection] = JsonDecoder.derived
}

final case class TokenResponse(access_token: String, refresh_token: String)

object TokenResponse {
  given JsonEncoder[TokenResponse] = JsonEncoder.derived
  given JsonDecoder[TokenResponse] = JsonDecoder.derived
}

trait KeycloakClient {

  def getRpt(accessToken: String): IO[AuthenticationError, String]

  def getAccessToken(username: String, password: String): IO[AuthenticationError, TokenResponse]

  def introspectToken(token: String): IO[AuthenticationError, TokenIntrospection]

  /** Return list of permitted resources */
  def checkPermissions(rpt: String): IO[AuthenticationError, List[String]]

}

class KeycloakClientImpl(client: AuthzClient, httpClient: Client, keycloakConfig: KeycloakConfig)
    extends KeycloakClient {

  private val introspectionUrl = client.getServerConfiguration().getIntrospectionEndpoint()
  private val tokenUrl = client.getServerConfiguration().getTokenEndpoint()

  private val baseFormHeaders = Headers(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))

  // TODO: support offline introspection
  // TODO: tests
  // https://www.keycloak.org/docs/22.0.4/securing_apps/#_token_introspection_endpoint
  override def introspectToken(token: String): IO[AuthenticationError, TokenIntrospection] = {
    for {
      response <- Client
        .request(
          url = introspectionUrl,
          method = Method.POST,
          headers = baseFormHeaders ++ Headers(
            Header.Authorization.Basic(keycloakConfig.clientId, keycloakConfig.clientSecret)
          ),
          content = Body.fromURLEncodedForm(
            Form(
              FormField.simpleField("token", token)
            )
          )
        )
        .logError("Fail to introspect token on keycloak.")
        .mapError(e => AuthenticationError.UnexpectedError("Fail to introspect the token on keyclaok."))
        .provide(ZLayer.succeed(httpClient))
      body <- response.body.asString
        .logError("Fail parse keycloak introspection response.")
        .mapError(e => AuthenticationError.UnexpectedError("Fail parse keycloak introspection response."))
      result <-
        if (response.status.code == 200) {
          ZIO
            .fromEither(body.fromJson[TokenIntrospection])
            .logError("Fail to decode keycloak token introspection response")
            .mapError(e => AuthenticationError.UnexpectedError(e))
        } else {
          ZIO.logError(s"Keycloak token introspection was unsucessful. Status: ${response.status}. Response: $body") *>
            ZIO.fail(AuthenticationError.UnexpectedError("Token introspection was unsuccessful."))
        }
    } yield result
  }

  override def getAccessToken(username: String, password: String): IO[AuthenticationError, TokenResponse] = {
    for {
      response <- Client
        .request(
          url = tokenUrl,
          method = Method.POST,
          headers = baseFormHeaders,
          content = Body.fromURLEncodedForm(
            Form(
              FormField.simpleField("grant_type", "password"),
              FormField.simpleField("client_id", keycloakConfig.clientId),
              FormField.simpleField("client_secret", keycloakConfig.clientSecret),
              FormField.simpleField("username", username),
              FormField.simpleField("password", password),
            )
          )
        )
        .logError("Fail to get the accessToken on keyclaok.")
        .mapError(e => AuthenticationError.UnexpectedError("Fail to get the accessToken on keyclaok."))
        .provide(ZLayer.succeed(httpClient))
      body <- response.body.asString
        .logError("Fail parse keycloak token response.")
        .mapError(e => AuthenticationError.UnexpectedError("Fail parse keycloak token response."))
      result <-
        if (response.status.code == 200) {
          ZIO
            .fromEither(body.fromJson[TokenResponse])
            .logError("Fail to decode keycloak token response")
            .mapError(e => AuthenticationError.UnexpectedError(e))
        } else {
          ZIO.logError(s"Keycloak token introspection was unsucessful. Status: ${response.status}. Response: $body") *>
            ZIO.fail(AuthenticationError.UnexpectedError("Token introspection was unsuccessful."))
        }
    } yield result
  }

  override def getRpt(accessToken: String): IO[AuthenticationError, String] =
    ZIO
      .attemptBlocking {
        val authResource = client.authorization(accessToken)
        val request = AuthorizationRequest()
        authResource.authorize(request)
      }
      .logError
      .mapBoth(
        e => AuthenticationError.UnexpectedError(e.getMessage()),
        response => response.getToken()
      )

  override def checkPermissions(rpt: String): IO[AuthenticationError, List[String]] =
    for {
      introspection <- ZIO
        .attemptBlocking(client.protection().introspectRequestingPartyToken(rpt))
        .logError
        .mapError(e => AuthenticationError.UnexpectedError(e.getMessage()))
      permissions = introspection.getPermissions().asScala.toList
    } yield permissions.map(_.getResourceId())

}

object KeycloakClientImpl {
  val layer: RLayer[KeycloakConfig & Client, KeycloakClient] =
    authzClientLayer >>> ZLayer.fromFunction(KeycloakClientImpl(_, _, _))

  def authzClientLayer: RLayer[KeycloakConfig, AuthzClient] = ZLayer.fromZIO {
    for {
      keycloakConfig <- ZIO.service[KeycloakConfig]
      config = KeycloakAuthzConfig(
        keycloakConfig.keycloakUrl.toString(),
        keycloakConfig.realmName,
        keycloakConfig.clientId,
        Map("secret" -> keycloakConfig.clientSecret).asJava,
        null
      )
      client <- ZIO.attempt(AuthzClient.create(config))
    } yield client
  }
}
