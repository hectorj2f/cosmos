package com.mesosphere.cosmos.render

import com.mesosphere.cosmos.bijection.CosmosConversions._
import com.mesosphere.cosmos.circe.Decoders.decode64
import com.mesosphere.cosmos.circe.Decoders.parse
import com.mesosphere.cosmos.circe.Decoders.parse64
import com.mesosphere.cosmos.error.CosmosException
import com.mesosphere.cosmos.error.JsonDecodingError
import com.mesosphere.cosmos.error.JsonParsingError
import com.mesosphere.cosmos.error.MarathonTemplateMustBeJsonObject
import com.mesosphere.cosmos.error.OptionsNotAllowed
import com.mesosphere.cosmos.label
import com.mesosphere.cosmos.model.StorageEnvelope
import com.mesosphere.cosmos.thirdparty.marathon.model.AppId
import com.mesosphere.cosmos.thirdparty.marathon.model.MarathonApp
import com.mesosphere.error.ResultOps
import com.mesosphere.universe
import com.mesosphere.universe.test.TestingPackages
import com.mesosphere.universe.v3.model._
import com.netaporter.uri.dsl._
import com.twitter.bijection.Conversion.asMethod
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax._
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.scalatest.Assertion
import org.scalatest.FreeSpec
import org.scalatest.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.slf4j.Logger
import scala.io.Source

class PackageDefinitionRendererSpec extends FreeSpec with Matchers with TableDrivenPropertyChecks {
  val logger: Logger = org.slf4j.LoggerFactory.getLogger(getClass)

  "if .labels from .marathon.v2AppMustacheTemplate " - {
    "isn't Map[String, String] an error is returned" in {
      forAll(TestingPackages.packageDefinitions) { pkgDef =>
        val mustache =
          """
            |{
            |  "labels": {
            |    "idx": 0,
            |    "string": "value"
            |  }
            |}
          """.
            stripMargin

        val pd = packageDefinition(pkgDef)(mustache)

        val exception = intercept[CosmosException](PackageDefinitionRenderer.renderMarathonV2App("http://someplace", pd, None, None))

        exception.error match {
          case js: JsonDecodingError =>
            assertResult("Unable to decode the JSON value as a scala.collection.immutable.Map")(js.message)
          case _ =>
            fail("expected JsonDecodingError")
        }
      }
    }

    "does not exist, a default empty object is used" in {
      forAll(TestingPackages.packageDefinitions) { pkgDef =>
        val mustache =
          """
            |{
            |  "env": {
            |    "some": "thing"
            |  }
            |}
          """.
            stripMargin

        val pd = packageDefinition(pkgDef)(mustache)

        val Right(some) = PackageDefinitionRenderer.renderMarathonV2App(
          "http://someplace",
          pd,
          None,
          None
        ).get.asJson.hcursor.downField("env").downField("some").as[String]

        assertResult("thing")(some)
      }
    }

    "is Map[String, String] is left in tact" in {
      forAll(TestingPackages.packageDefinitions) { pkgDef =>
        val json = Json.obj(
          "labels" -> Json.obj(
            "a" -> "A".asJson,
            "b" -> "B".asJson
          )
        )
        val pkg = packageDefinition(pkgDef)(json.noSpaces)

        val Some(rendered) = PackageDefinitionRenderer.renderMarathonV2App("http://someplace", pkg, None, None)

        val Right(labels) = rendered.asJson.hcursor.get[Map[String, String]]("labels")
        assertResult("A")(labels("a"))
        assertResult("B")(labels("b"))
      }
    }
  }

  "Merging JSON objects" - {

    "should happen as part of marathon AppDefinition rendering" in {
      forAll(Examples) { (defaultsJson, optionsJson, mergedJson) =>
        val packageName = "options-test"
        val mustacheTemplate = buildMustacheTemplate(mergedJson)
        val mustacheBytes = ByteBuffer.wrap(mustacheTemplate.getBytes(StandardCharsets.UTF_8))

        val packageDefinition = V3Package(
          packagingVersion = V3PackagingVersion,
          name = packageName,
          version = Version("1.2.3"),
          maintainer = "Mesosphere",
          description = "Testing user options",
          releaseVersion = ReleaseVersion(0),
          marathon = Some(Marathon(mustacheBytes)),
          config = Some(buildConfig(Json.fromJsonObject(defaultsJson)))
        )

        val Some(marathonJson) = PackageDefinitionRenderer.renderMarathonV2App(
          "http://someplace",
          packageDefinition,
          Some(optionsJson),
          None
        )

        val expectedOptions = keyValify(mergedJson)
        val hasAllOptions = expectedOptions.forall { case (k, v) =>
          marathonJson(k).map(_.toString).contains(v)
        }

        assert(hasAllOptions)
      }
    }

    "should correctly follow the priority [" +
      "config defaults -> " +
      "user options -> " +
      "resources -> " +
      "required labels -> " +
      "template rendered labels -> " +
      "non overridable labels -> " +
      "user specified appId" +
      "]" in {
      val s = classpathJsonString("/com/mesosphere/cosmos/render/test-schema.json")
      val schema = parse(s).getOrThrow.asObject.get

      val mustache =
        """
          |{
          |  "id": "{{opt.id}}",
          |  "uri": "{{resource.assets.uris.blob}}",
          |  "labels": {
          |    "DCOS_PACKAGE_NAME": "{{opt.name}}"
          |  }
          |}
        """.stripMargin
      val mustacheBytes = ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8))

      val pkg = V2Package(
        name = "test",
        version = Version("1.2.3"),
        releaseVersion = ReleaseVersion(0),
        maintainer = "maintainer",
        description = "description",
        marathon = Marathon(mustacheBytes),
        config = Some(schema),
        resource = Some(V2Resource(
          assets = Some(Assets(
            uris = Some(Map(
              "blob" -> "http://someplace/blob"
            )),
            container = None
          ))
        )),
        command = Some(Command(List("something-not-overridden-cmd")))
      )

      val options = Json.obj(
        "opt" -> Json.obj(
          "id" -> "should-be-overridden-id".asJson,
          "name" -> "testing-name".asJson
        ),
        "resource" -> Json.obj(
          "assets" -> Json.obj(
            "uris" -> Json.obj(
              "blob" -> "should-be-overridden-blob".asJson
            )
          )
        )
      ).asObject.get

      val appId = AppId("/override")
      val renderedFocus = PackageDefinitionRenderer.renderMarathonV2App(
        "http://someplace",
        pkg,
        Some(options),
        Some(appId)
      ).get.asJson.hcursor

      renderedFocus.get[AppId]("id") shouldBe Right(appId)
      renderedFocus.get[String]("uri") shouldBe Right("http://someplace/blob")

      // Test that all of the labels are set correctly
      val labelFocus = renderedFocus.downField("labels")

      labelFocus.get[String](MarathonApp.nameLabel) shouldBe Right("test")
      labelFocus.get[String](MarathonApp.repositoryLabel) shouldBe Right("http://someplace")
      labelFocus.get[String](MarathonApp.versionLabel) shouldBe Right("1.2.3")
      labelFocus.get[String](MarathonApp.optionsLabel).map(
        parse64(_)
      ) shouldBe Right(options.asJson)
      labelFocus.get[String](MarathonApp.metadataLabel).map(
        decode64[label.v1.model.PackageMetadata](_)
      ) shouldBe Right(pkg.as[label.v1.model.PackageMetadata])
      labelFocus.get[String](MarathonApp.packageLabel).map(
        decode64[StorageEnvelope](_)
      ) shouldBe Right(StorageEnvelope(pkg))
    }

  }

  "renderMarathonV2App should" - {
    "result in error if no marathon template defined" in {
      val pkg = V3Package(
        name = "test",
        version = Version("1.2.3"),
        releaseVersion = ReleaseVersion(0),
        maintainer = "maintainer",
        description = "description"
      )

      val response = PackageDefinitionRenderer.renderMarathonV2App("http://someplace", pkg, None, None)
      assertResult(None)(response)

    }

    "result in error if options provided but no config defined" in {
      val mustache = """{"id": "{{option.id}}"}"""
      val mustacheBytes = ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8))
      val pkg = V3Package(
        name = "test",
        version = Version("1.2.3"),
        releaseVersion = ReleaseVersion(0),
        maintainer = "maintainer",
        description = "description",
        marathon = Some(Marathon(mustacheBytes))
      )
      val options = Json.obj(
        "option" -> Json.obj(
          "id" -> "should-be-overridden".asJson
        )
      ).asObject.get

      val exception = intercept[CosmosException](PackageDefinitionRenderer.renderMarathonV2App("http://someplace", pkg, Some(options), None))
      assertResult(OptionsNotAllowed())(exception.error)
    }

    "result in error if rendered template is not valid json" in {
      val mustache = """{"id": "broken""""
      val mustacheBytes = ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8))
      val pkg = V3Package(
        name = "test",
        version = Version("1.2.3"),
        releaseVersion = ReleaseVersion(0),
        maintainer = "maintainer",
        description = "description",
        marathon = Some(Marathon(mustacheBytes))
      )

      val exception = intercept[CosmosException](PackageDefinitionRenderer.renderMarathonV2App("http://someplace", pkg, None, None))
      exception.error shouldBe a[JsonParsingError]
      assertResult(exception.error.message)("Unable to parse the string as a JSON value")
    }

    "result in error if rendered template is valid json but is not valid json object" in {
      val mustache = """["not-an-object"]"""
      val mustacheBytes = ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8))
      val pkg = V3Package(
        name = "test",
        version = Version("1.2.3"),
        releaseVersion = ReleaseVersion(0),
        maintainer = "maintainer",
        description = "description",
        marathon = Some(Marathon(mustacheBytes))
      )

      val exception = intercept[CosmosException](PackageDefinitionRenderer.renderMarathonV2App("http://someplace", pkg, None, None))
      exception.error shouldBe MarathonTemplateMustBeJsonObject
    }

    "enforce appId is set to argument passed to argument if Some" in {
      val mustache = """{"id": "{{option.id}}"}"""
      val mustacheBytes = ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8))
      val pkg = V2Package(
        name = "test",
        version = Version("1.2.3"),
        releaseVersion = ReleaseVersion(0),
        maintainer = "maintainer",
        description = "description",
        marathon = Marathon(mustacheBytes),
        config = Some(buildConfig(Json.obj(
          "option" -> Json.obj(
            "id" -> "default".asJson
          )
        )))
      )

      val options = Json.obj(
        "option" -> Json.obj(
          "id" -> "should-be-overridden".asJson
        )
      ).asObject.get

      val appId = AppId("/override")
      val rendered = PackageDefinitionRenderer.renderMarathonV2App(
        "http://someplace",
        pkg,
        Some(options),
        Some(appId)
      ).get.asJson

      val Right(actualAppId) = rendered.hcursor.get[AppId]("id")
      assertResult(appId)(actualAppId)
    }

    "property add resource object to options" - {
      "V2Package" in {
        val mustache =
          """{
            |  "some": "{{resource.assets.uris.blob}}"
            |}""".stripMargin
        val mustacheBytes = ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8))
        val pkg = V2Package(
          name = "test",
          version = Version("1.2.3"),
          releaseVersion = ReleaseVersion(0),
          maintainer = "maintainer",
          description = "description",
          marathon = Marathon(mustacheBytes),
          resource = Some(V2Resource(
            assets = Some(Assets(
              uris = Some(Map(
                "blob" -> "http://someplace/blob"
              )),
              container = None
            ))
          ))
        )
        val rendered = PackageDefinitionRenderer.renderMarathonV2App(
          "http://someplace",
          pkg,
          None,
          None
        ).get.asJson

        val Right(renderedValue) = rendered.hcursor.get[String]("some")
        assertResult("http://someplace/blob")(renderedValue)
      }

      "V3Package" in {
        val mustache =
          """{
            |  "some": "{{resource.assets.uris.blob}}"
            |}""".stripMargin
        val mustacheBytes = ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8))
        val pkg = V3Package(
          name = "test",
          version = Version("1.2.3"),
          releaseVersion = ReleaseVersion(0),
          maintainer = "maintainer",
          description = "description",
          marathon = Some(Marathon(mustacheBytes)),
          resource = Some(V3Resource(
            assets = Some(Assets(
              uris = Some(Map(
                "blob" -> "http://someplace/blob"
              )),
              container = None
            ))
          ))
        )
        val rendered = PackageDefinitionRenderer.renderMarathonV2App(
          "http://someplace",
          pkg,
          None,
          None
        ).get.asJson

        val Right(renderedValue) = rendered.hcursor.get[String]("some")
        assertResult("http://someplace/blob")(renderedValue)
      }

      "V4Package" in {
        val mustache =
          """{
            |  "some": "{{resource.assets.uris.blob}}"
            |}""".stripMargin
        val mustacheBytes = ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8))
        val pkg = universe.v4.model.V4Package(
          name = "test",
          version = Version("1.2.3"),
          releaseVersion = ReleaseVersion(0),
          maintainer = "maintainer",
          description = "description",
          marathon = Some(Marathon(mustacheBytes)),
          resource = Some(V3Resource(
            assets = Some(Assets(
              uris = Some(Map(
                "blob" -> "http://someplace/blob"
              )),
              container = None
            ))
          )),
          upgradesFrom = Some(List()),
          downgradesTo = Some(List())
        )
        val rendered = PackageDefinitionRenderer.renderMarathonV2App(
          "http://someplace",
          pkg,
          None,
          None
        ).get.asJson

        val Right(renderedValue) = rendered.hcursor.get[String]("some")
        assertResult("http://someplace/blob")(renderedValue)
      }
    }
  }

  "renderTemplate" - {
    "should not use html encoding for special characters" in {
      val template =
        """
          |{
          |  "string": "{{stringExample}}",
          |  "simpleString": "{{simpleStringExample}}",
          |  "htmlString": "{{htmlStringExample}}",
          |  "int": {{intExample}},
          |  "double": {{doubleExample}},
          |  "boolean": {{booleanExample}}
          |}
          |""".stripMargin

      val context = JsonObject.fromMap(
        Map(
          ("stringExample", "\n\'\"\\\r\t\b\f".asJson),
          ("simpleStringExample", "foo\"bar".asJson),
          ("htmlStringExample", "<a>Foo&Bar Inc.</a>".asJson),
          ("intExample", 42.asJson),
          ("doubleExample", 42.1.asJson),
          ("booleanExample", Json.False)
        )
      )

      PackageDefinitionRenderer.renderTemplate(
        template,
        context
      ) shouldBe JsonObject.fromMap(
        Map(
          ("string", "\n\'\"\\\r\t\b\f".asJson),
          ("simpleString", "foo\"bar".asJson),
          ("htmlString", "<a>Foo&Bar Inc.</a>".asJson),
          ("int", 42.asJson),
          ("double", 42.1.asJson),
          ("boolean", Json.False)
        )
      )
    }

    val instanceBlob =
      """
        |{
        |  "a-numeric-value" : 1,
        |  "another-numeric-value" : 2.2,
        |  "a-boolean-key" : true,
        |  "a-simple-string" : "foobar",
        |  "a-numeric-array" : [1, 2, 3],
        |  "2d-numeric-array": [[1, 2], [3, 4]],
        |  "a-boolean-array" : [true, false, true],
        |  "2d-boolean-array" : [[true, false, true], [true, false, true]],
        |  "a-float-array" : [1, 2, 3],
        |  "2d-float-array" : [[1, 2, 3], [1, 2, 3]],
        |  "a-string-array" : ["one", "two", "three"],
        |  "2d-string-array" : [["one", "two", "three"], ["one", "two", "three"]],
        |  "an-ascii-string" : "foobar!@#$%^&*()_+{}[]|';:./,<>?`~",
        |  "a-unicode-string" : "ÿöłø"
        |}
      """.stripMargin

    val context = s"""{"service":$instanceBlob}"""

    val templateInstanceBlob =
      """
        |{
        |  "a-numeric-value" : {{service.a-numeric-value}},
        |  "another-numeric-value" : {{service.another-numeric-value}},
        |  "a-boolean-key" : {{service.a-boolean-key}},
        |  "a-simple-string" : "{{service.a-simple-string}}",
        |  "a-numeric-array" : {{service.a-numeric-array}},
        |  "2d-numeric-array": {{service.2d-numeric-array}},
        |  "a-boolean-array" : {{service.a-boolean-array}},
        |  "2d-boolean-array" : {{service.2d-boolean-array}},
        |  "a-float-array" : {{service.a-float-array}},
        |  {{#service.2d-float-array}}
        |  "2d-float-array" : {{service.2d-float-array}},
        |  {{/service.2d-float-array}}
        |  {{#service.non-existent-array}}
        |  "absent-key" : "absent-value",
        |  {{/service.non-existent-array}}
        |  {{^service.non-existent-value}}
        |  "a-string-array" : {{service.a-string-array}},
        |  {{/service.non-existent-value}}
        |  "2d-string-array" : {{service.2d-string-array}},
        |  "an-ascii-string" : "{{service.an-ascii-string}}",
        |  "a-unicode-string" : "{{service.a-unicode-string}}"
        |}
      """.stripMargin

    "should work for a simple JSON instance data model" in {
      checkPlainTextRenders(
        mustacheTemplate =
          s"""
             |{
             |  "blob" : $templateInstanceBlob,
             |  "array" : [$templateInstanceBlob, $templateInstanceBlob],
             |  "jsonObj" : {"key1":$templateInstanceBlob, "key2":$templateInstanceBlob}
             |}
          """.stripMargin,
        context,
        expected =
          s"""
             |{
             | "blob" : $instanceBlob,
             | "array" : [$instanceBlob, $instanceBlob],
             | "jsonObj" : {"key1":$instanceBlob, "key2":$instanceBlob}
             |}
        """.stripMargin
      )
    }

    val nestedTemplateInstanceBlob =
      s"""
         |{
         | "nestedJson" : $templateInstanceBlob,
         | "arrayJson" : [$templateInstanceBlob, $templateInstanceBlob],
         | "nestedArray": [{"key1": $templateInstanceBlob},{"key2": $templateInstanceBlob}]
         |}
        """.stripMargin

    val expectedNestedTemplateInstanceBlob =
      s"""
         |{
         | "nestedJson" : $instanceBlob,
         | "arrayJson" : [$instanceBlob, $instanceBlob],
         | "nestedArray": [{"key1": $instanceBlob},{"key2": $instanceBlob}]
         |}
        """.stripMargin

    "should work for a nested JSON instance data model" in {
      checkPlainTextRenders(
        mustacheTemplate =
          s"""{"nd-array": [[[[$templateInstanceBlob, $templateInstanceBlob]
             |,[$templateInstanceBlob, $templateInstanceBlob]]]]}""".stripMargin,
        context,
        expected =
          s"""{"nd-array": [[[[$instanceBlob, $instanceBlob],[$instanceBlob, $instanceBlob]]]]}"""
      )

      checkPlainTextRenders(
        mustacheTemplate =
          s"""
             |{
             |  "blob" : $nestedTemplateInstanceBlob,
             |  "array" : [$nestedTemplateInstanceBlob, $nestedTemplateInstanceBlob],
             |  "jsonObj" : {
             |    "key1":$nestedTemplateInstanceBlob,
             |    "key2":$nestedTemplateInstanceBlob
             |  }
             |}
          """.stripMargin,
        context,
        expected =
          s"""
             |{
             | "blob" : $expectedNestedTemplateInstanceBlob,
             | "array" : [
             |   $expectedNestedTemplateInstanceBlob,
             |   $expectedNestedTemplateInstanceBlob
             | ],
             | "jsonObj" : {
             |   "key1":$expectedNestedTemplateInstanceBlob,
             |   "key2":$expectedNestedTemplateInstanceBlob
             | }
             |}
        """.stripMargin
      )
    }
  }

  private[this] def checkPlainTextRenders(
    mustacheTemplate: String,
    context: String,
    expected: String
  ): Assertion = {
    val parsedContext = parse(context).toOption.get.asObject.get
    val parsedExpected = parse(expected).toOption.get
    checkJsonRenders(mustacheTemplate, parsedContext, parsedExpected)
  }

  private[this] def checkJsonRenders(
    mustacheTemplate: String,
    context: JsonObject,
    expected: Json
  ): Assertion = {
    logger.info(s"Mustache template:\n$mustacheTemplate\nContextJsonObject" +
      s":\n$context\nExpectedJson:\n$expected\n")
    val result = PackageDefinitionRenderer.renderTemplate(mustacheTemplate, context)
    result.asJson.noSpaces shouldEqual expected.noSpaces
  }

  private[this] val Examples = Table(
    ("defaults JSON", "options JSON", "merged JSON"),
    (JsonObject.empty, JsonObject.empty, JsonObject.empty),

    (JsonObject.empty,
      JsonObject.singleton("a", Json.False),
      JsonObject.singleton("a", Json.False)),

    (JsonObject.singleton("a", Json.False),
      JsonObject.empty,
      JsonObject.singleton("a", Json.False)),

    (JsonObject.singleton("a", Json.False),
      JsonObject.singleton("a", Json.False),
      JsonObject.singleton("a", Json.False)),

    (JsonObject.singleton("a", Json.obj("a" -> Json.False)),
      JsonObject.singleton("a", Json.obj()),
      JsonObject.singleton("a", Json.obj("a" -> Json.False))),

    (JsonObject.singleton("a", Json.obj("a" -> Json.False)),
      JsonObject.singleton("a", Json.obj("a" -> Json.False)),
      JsonObject.singleton("a", Json.obj("a" -> Json.False))),

    (JsonObject.singleton("a", Json.obj("a" -> Json.False)),
      JsonObject.singleton("a", Json.obj("b" -> Json.False)),
      JsonObject.singleton("a", Json.obj("a" -> Json.False, "b" -> Json.False)))
  )

  private[this] def keyValify(mustacheScopeJson: JsonObject): Map[String, String] = {
    keyValifyMap(mustacheScopeJson.toMap, Vector.empty).toMap
  }

  private[this] def keyValifyMap(
    jsonMap: Map[String, Json],
    path: Seq[String]
  ): TraversableOnce[(String, String)] = {
    jsonMap
      .flatMap { case (key, value) =>
        keyValifyJson(value, path :+ key)
      }
  }

  private[this] def keyValifyJson(
    json: Json,
    path: Seq[String]
  ): TraversableOnce[(String, String)] = {
    lazy val joinedPath = path.mkString(".")
    json.fold(
      jsonNull = Seq((joinedPath, "null")),
      jsonBoolean = boolean => Seq((joinedPath, boolean.toString)),
      jsonNumber = number => Seq((joinedPath, number.toString)),
      jsonString = string => Seq((joinedPath, string)),
      jsonArray = { elements =>
        val indexMap = elements
          .zipWithIndex
          .map { case (j, i) => (i.toString, j) }
          .toMap
        keyValifyMap(indexMap, path)
      },
      jsonObject = obj => keyValifyMap(obj.toMap, path)
    )
  }

  private[this] def buildConfig(defaultsJson: Json): JsonObject = {
    defaultsJson.fold(
      jsonNull = JsonObject.fromMap(Map("type" -> "null".asJson, "default" -> defaultsJson)),
      jsonBoolean = boolean => JsonObject.fromMap(Map("type" -> "boolean".asJson, "default" -> defaultsJson)),
      jsonNumber = number => JsonObject.fromMap(Map("type" -> "number".asJson, "default" -> defaultsJson)),
      jsonString = string => JsonObject.fromMap(Map("type" -> "string".asJson, "default" -> defaultsJson)),
      jsonArray = array => JsonObject.fromMap(Map("type" -> "array".asJson, "default" -> defaultsJson)),
      jsonObject = { obj =>
        JsonObject.fromMap(Map(
          "type" -> "object".asJson,
          "properties" -> obj.toMap.mapValues(buildConfig).asJson
        ))
      }
    )
  }

  private[this] def buildMustacheTemplate(mustacheScopeJson: JsonObject): String = {
    val parameters = keyValify(mustacheScopeJson)
      .keysIterator
      .map(name => (name, s"{{$name}}"))
      .toMap

    (parameters + (("id", "\"options-test\"")))
      .map { case (name, value) => s""""$name":$value""" }
      .mkString("{", ",", "}")
  }


  private[this] def packageDefinition(pkgDef: universe.v4.model.PackageDefinition)(mustache: String) = {
    val marathon = Marathon(ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8)))
    pkgDef match {
      case v2: universe.v3.model.V2Package =>
        v2.copy(marathon = marathon)
      case v3: universe.v3.model.V3Package =>
        v3.copy(marathon = Some(marathon))
      case v4: universe.v4.model.V4Package =>
        v4.copy(marathon = Some(marathon))
      case v5: universe.v5.model.V5Package =>
        v5.copy(marathon = Some(marathon))
    }
  }

  private[this] def classpathJsonString(resourceName: String): String = {
    Option(this.getClass.getResourceAsStream(resourceName)) match {
      case Some(is) => Source.fromInputStream(is).mkString
      case _ => throw new IllegalStateException(s"Unable to load classpath resource: $resourceName")
    }
  }

}
