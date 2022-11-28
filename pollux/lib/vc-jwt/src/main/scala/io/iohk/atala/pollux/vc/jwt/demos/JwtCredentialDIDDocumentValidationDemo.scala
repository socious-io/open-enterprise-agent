package io.iohk.atala.pollux.vc.jwt.demos

import cats.implicits.*
import com.nimbusds.jose.jwk.*
import com.nimbusds.jose.jwk.gen.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.iohk.atala.pollux.vc.jwt.*
import io.iohk.atala.pollux.vc.jwt.CredentialPayload.Implicits.*
import io.iohk.atala.pollux.vc.jwt.schema.{PlaceholderSchemaValidator, ReactiveCoreSchemaValidator}
import net.reactivecore.cjs.resolver.Downloader
import net.reactivecore.cjs.{DocumentValidator, Loader, Result}
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}
import zio.*
import zio.Console.*

import java.security.*
import java.security.interfaces.{ECPrivateKey, ECPublicKey}
import java.security.spec.*
import java.time.{Instant, ZonedDateTime}
import java.util.UUID
import scala.collection.immutable.Set

object JwtCredentialDIDDocumentValidationDemo extends ZIOAppDefault {
  def run =

    def toJWKFormat(holderJwk: ECKey): JsonWebKey = {
      JsonWebKey(
        kty = "EC",
        crv = Some(holderJwk.getCurve.getName),
        x = Some(holderJwk.getX.toJSONString),
        y = Some(holderJwk.getY.toJSONString),
        d = Some(holderJwk.getD.toJSONString)
      )
    }

    def createUser(did: DID) = {
      val keyGen = KeyPairGenerator.getInstance("EC")
      keyGen.initialize(Curve.P_256.toECParameterSpec)
      val keyPair = keyGen.generateKeyPair()
      val privateKey = keyPair.getPrivate
      val publicKey = keyPair.getPublic
      (
        Issuer(
          did = did,
          signer = ES256Signer(privateKey),
          publicKey = publicKey
        ),
        new ECKey.Builder(Curve.P_256, publicKey.asInstanceOf[ECPublicKey])
          .privateKey(privateKey.asInstanceOf[ECPrivateKey])
          .build()
      )
    }

    println("")
    println("==================")
    println("Create Issuer1")
    println("==================")
    val (issuer1, issuer1Jwk) =
      createUser(DID("did:issuer1:MDP8AsFhHzhwUvGNuYkX7T"))
    println(issuer1)

    println("")
    println("==================")
    println("Create Issuer2")
    println("==================")
    val (issuer2, issuer2Jwk) =
      createUser(DID("did:issuer2:MDP8AsFhHzhwUvGNuYkX7T"))
    println(issuer2)

    println("")
    println("==================")
    println("Create Issuer2")
    println("==================")
    val (issuer3, issuer3Jwk) =
      createUser(DID("did:issuer3:MDP8AsFhHzhwUvGNuYkX7T"))
    println(issuer3)

    println("")
    println("==================")
    println("Create JWT Credential")
    println("==================")
    val jwtCredentialPayload =
      JwtCredentialPayload(
        maybeIss = Some("https://example.edu/issuers/565049"), // ISSUER DID
        maybeSub = Some("1"), // SUBJECT DID
        vc = JwtVc(
          `@context` = Set("https://www.w3.org/2018/credentials/v1", "https://www.w3.org/2018/credentials/examples/v1"),
          `type` = Set("VerifiableCredential", "UniversityDegreeCredential"),
          maybeCredentialSchema = Some(
            CredentialSchema(
              id = "did:work:MDP8AsFhHzhwUvGNuYkX7T;id=06e126d1-fa44-4882-a243-1e326fbe21db;version=1.0",
              `type` = "JsonSchemaValidator2018"
            )
          ),
          credentialSubject = Json.obj(
            "id" -> Json.fromString("1"),
            "userName" -> Json.fromString("Bob"),
            "age" -> Json.fromInt(42),
            "email" -> Json.fromString("email")
          ),
          maybeCredentialStatus = Some(
            CredentialStatus(
              id = "did:work:MDP8AsFhHzhwUvGNuYkX7T;id=06e126d1-fa44-4882-a243-1e326fbe21db;version=1.0",
              `type` = "CredentialStatusList2017"
            )
          ),
          maybeRefreshService = Some(
            RefreshService(
              id = "https://example.edu/refresh/3732",
              `type` = "ManualRefreshService2018"
            )
          ),
          maybeEvidence = Option.empty,
          maybeTermsOfUse = Option.empty
        ),
        maybeNbf = Some(Instant.parse("2010-01-01T00:00:00Z")), // ISSUANCE DATE
        aud = Set.empty,
        maybeExp = Some(Instant.parse("2010-01-12T00:00:00Z")), // EXPIRATION DATE
        maybeJti = Some("http://example.edu/credentials/3732") // CREDENTIAL ID
      )

    def credentialSchemaResolver(credentialSchema: CredentialSchema): Json = {
      val resolvedSchema =
        """
          |{
          |  "type": "object",
          |  "properties": {
          |    "userName": {
          |      "$ref": "#/$defs/user"
          |    },
          |    "age": {
          |      "$ref": "#/$defs/age"
          |    },
          |    "email": {
          |      "$ref": "#/$defs/email"
          |    }
          |  },
          |  "required": ["userName", "age", "email"],
          |  "$defs": {
          |    "user": {
          |       "type": "string",
          |       "minLength": 3
          |     },
          |     "age": {
          |       "type": "number"
          |     },
          |     "email": {
          |       "type": "string",
          |       "format": "email"
          |     }
          |  }
          |}
          |""".stripMargin
      io.circe.parser.parse(resolvedSchema).toOption.get
    }

    class DidResolverTest() extends DidResolver {

      override def resolve(didUrl: String): IO[Throwable, DIDResolutionResult] = {
        val Issuer1Key =
          VerificationMethod(
            id = "Issuer1Key",
            `type` = JwtAlgorithm.ES256.name,
            controller = "",
            publicKeyJwk = Some(
              toJWKFormat(issuer1Jwk)
            )
          )
        val issuer2Key = VerificationMethod(
          id = "Issuer2Key",
          `type` = JwtAlgorithm.ES256.name,
          controller = "",
          publicKeyJwk = Some(
            toJWKFormat(issuer2Jwk)
          )
        )
        val issuer3Key = VerificationMethod(
          id = "Issuer3Key",
          `type` = JwtAlgorithm.ES256.name,
          controller = "",
          publicKeyJwk = Some(
            toJWKFormat(issuer3Jwk)
          )
        )
        val didDocument = DIDDocument(
          id = "Test",
          alsoKnowAs = Vector.empty,
          controller = Vector.empty,
          verificationMethod = Vector(
            Issuer1Key, // <------ ISSUER PUBLIC-KEY 1
            issuer2Key, // <------ ISSUER PUBLIC-KEY 2
            issuer3Key // <------ ISSUER PUBLIC-KEY 2
          ),
          service = Vector.empty
        )
        ZIO.succeed(
          DIDResolutionSucceeded(
            didDocument, // <------ DID DOCUMENT
            "",
            DIDDocumentMetadata()
          )
        )
      }
    }

    println("")
    println("==================")
    println("Encode JWT Credential")
    println("==================")
    val encodedJwt = JwtCredential.encodeJwt(jwtCredentialPayload, issuer = issuer3)

    println("")
    println("==================")
    println("Validate JWT Credential Using DID Document of the Issuer of the Credential")
    println("==================")
    val validator =
      JwtCredential.validateEncodedJWT(encodedJwt)(DidResolverTest())(credentialSchemaResolver)(
        PlaceholderSchemaValidator.fromSchema
      )

    for {
      _ <- printLine("DEMO TIME! ")
      result <- validator
      result2 <- result.toOption.get
      _ <- printLine(s"IS VALID?: ${result2.toOption.get}")
    } yield ()
}