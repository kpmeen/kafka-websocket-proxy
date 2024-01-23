package net.scalytica.kafka.wsproxy.auth

/**
 * @param issuer
 *   OpenID Connect supports multiple Issuers per Host and Port combination. The
 *   issuer returned by discovery MUST exactly match the value of iss in the ID
 *   Token.
 * @param jwksUri
 *   URL of the OP's JSON Web Key Set JWK document. This contains the signing
 *   key(s) used to validate signatures from the OP.
 * @param authorizationEndpoint
 *   URL of the OP's OAuth 2.0 Authorization Endpoint
 * @param tokenEndpoint
 *   URL of the OP's OAuth 2.0 Token Endpoint
 * @param responseTypesSupported
 *   List of the OAuth 2.0 {{response_type}} values that this OP supports.
 *   Dynamic OpenID Providers MUST support the {{code}}, {{id_token}}, and the
 *   {{token id_token}}.
 * @param subjectTypesSupported
 *   List of the Subject Identifier types that this OP supports. Valid types
 *   include pairwise and public.
 * @param idTokenSigningAlgValuesSupported
 *   List of the JWS signing algorithms ({{alg}} values) supported by the OP for
 *   the ID Token to encode the Claims in a JWT. The algorithm RS256 MUST be
 *   included.
 * @param tokenEndpointAuthMethodsSupported
 *   List of client authentication methods supported by the token endpoint. The
 *   options are {{client_secret_post}}, {{client_secret_basic}},
 *   {{client_secret_jwt}}, and {{private_key_jwt}}.
 * @param claimsSupported
 *   List of the Claim Names of the Claims that the OpenID Provider MAY be able
 *   to supply values for. Note that for privacy or other reasons, this might
 *   not be an exhaustive list.
 * @param grantTypesSupported
 *   List of the OAuth 2.0 Grant Type values that this OP supports. Dynamic
 *   OpenID Providers MUST support the {{authorization_code}} and {{implicit}}
 *   Grant Type values and MAY support other Grant Types.
 * @param scopesSupported
 *   List of the OAuth 2.0 [RFC6749] scope values that this server supports. The
 *   server MUST support the {{openid}} scope value. Servers MAY choose not to
 *   advertise some supported scope values even when this parameter is used,
 *   although those defined in [OpenID.Core] SHOULD be listed, if supported.
 */
case class OpenIdConnectConfig(
    issuer: String,
    jwksUri: String,
    authorizationEndpoint: String,
    tokenEndpoint: String,
    responseTypesSupported: List[String],
    subjectTypesSupported: List[String],
    idTokenSigningAlgValuesSupported: List[String],
    tokenEndpointAuthMethodsSupported: Option[List[String]] = None,
    claimsSupported: Option[List[String]] = None,
    grantTypesSupported: Option[List[String]] = None,
    scopesSupported: Option[List[String]] = None
) {

  override def toString: String = {
    // format: off
    // scalastyle:off
    s"""OpenIdConnectConfig(
       |  issuer                           =$issuer
       |  jwksUri                          =$jwksUri
       |  authorizationEndpoint            =$authorizationEndpoint
       |  tokenEndpoint                    =$tokenEndpoint
       |  responseTypesSupported           =${responseTypesSupported.mkString("[", ", ", "]")}
       |  subjectTypesSupported            =${subjectTypesSupported.mkString("[", ", ", "]")}
       |  idTokenSigningAlgValuesSupported =${idTokenSigningAlgValuesSupported.mkString("[", ", ", "]")}
       |  tokenEndpointAuthMethodsSupported=${tokenEndpointAuthMethodsSupported.map(_.mkString("[", ", ", "]")).getOrElse("[ ]")}
       |  claimsSupported                  =${claimsSupported.map(_.mkString("[", ", ", "]")).getOrElse("[ ]")}
       |  grantTypesSupported              =${grantTypesSupported.map(_.mkString("[", ", ", "]")).getOrElse("[ ]")}
       |  scopesSupported                  =${scopesSupported.map(_.mkString("[", ", ", "]")).getOrElse("[ ]")}
       |)""".stripMargin
    // scalastyle:on
    // format: on
  }

}
