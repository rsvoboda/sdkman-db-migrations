package io.sdkman

import com.github.mongobee.exception.MongobeeChangeSetException
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.{Filters, Updates}
import com.typesafe.scalalogging.LazyLogging
import org.bson.Document

import scala.language.implicitConversions

package object changelogs {

  val CandidatesCollection = "candidates"

  val VersionsCollection = "versions"

  implicit class DefaultCandidate(v: Version)(implicit db: MongoDatabase) {
    def asCandidateDefault(): Unit = setCandidateDefault(v.candidate, v.version)
  }

  trait Validator[A] {
    def validVersion(a: A): Unit

    def validUrl(a: A): Unit
  }

  implicit val versionValidation = new Validator[Version] with UrlValidation with LazyLogging {

    override def validVersion(v: Version): Unit =
      if (v.version.length > 15)
        throw new MongobeeChangeSetException(s"sVersion length exceeds 15 chars: ${v.version}")

    override def validUrl(v: Version): Unit = {
      if (v.url.contains("download.oracle.com")) {
        checkResourceAvailable(v.url, Some(Cookie("oraclelicense", "accept-securebackup-cookie")))
      } else {
        checkResourceAvailable(v.url, None)
      }
    }

    private def checkResourceAvailable(url: String, cookie: Option[Cookie]) = {
      val available = cookie.fold(resourceAvailable(url)) { c =>
        resourceAvailable(url, Some(c))
      }

      if (!available) throw new MongobeeChangeSetException(s"Invalid url: $url")
    }
  }

  implicit def listValidation[A](implicit validate: Validator[A]): Validator[List[A]] = new Validator[List[A]] {
    override def validVersion(la: List[A]): Unit = la.foreach(validate.validVersion)

    override def validUrl(la: List[A]): Unit = la.foreach(validate.validUrl)
  }

  trait Migrator[A] {
    def insert(a: A)(implicit db: MongoDatabase): Unit
  }

  implicit val candidateMigration = new Migrator[Candidate] {
    override def insert(c: Candidate)(implicit db: MongoDatabase): Unit =
      db.getCollection(CandidatesCollection)
        .insertOne(candidateToDocument(c))
  }

  implicit val versionMigration = new Migrator[Version] {
    override def insert(v: Version)(implicit db: MongoDatabase): Unit =
      db.getCollection(VersionsCollection)
        .insertOne(versionToDocument(v))
  }

  implicit def listMigration[A](implicit migratable: Migrator[A]): Migrator[List[A]] = new Migrator[List[A]] {
    override def insert(xs: List[A])(implicit db: MongoDatabase): Unit = xs.foreach(migratable.insert)
  }

  implicit class MigrationOps[A](a: A) {
    def insert()(implicit migrator: Migrator[A], db: MongoDatabase): A = {
      migrator.insert(a)
      a
    }
  }

  implicit class ValidationOps[A](a: A) {
    def validate()(implicit validator: Validator[A]): A = {
      validator.validUrl(a)
      validator.validVersion(a)
      a
    }
  }

  trait Vendor {
    def id: String
  }

  case object AdoptOpenJDK extends Vendor {
    override def id = "adopt"
  }

  case object Amazon extends Vendor {
    override def id = "amazon"
  }

  case object Graal extends Vendor {
    override def id = "graal"
  }

  case object Liberica extends Vendor {
    override def id = "liberica"
  }

  case object OpenJDK extends Vendor {
    override def id = "open"
  }

  case object SAP extends Vendor {
    override def id = "sap"
  }

  case object Zulu extends Vendor {
    override def id = "zulu"
  }

  trait Platform {
    def id: String
  }

  case object Universal extends Platform {
    override val id = "UNIVERSAL"
  }

  case object MacOSX extends Platform {
    override val id = "MAC_OSX"
  }

  case object Windows extends Platform {
    override val id = "WINDOWS_64"
  }

  case object Linux64 extends Platform {
    override val id = "LINUX_64"
  }

  case object Linux32 extends Platform {
    override val id = "LINUX_32"
  }

  case class Candidate(candidate: String,
                       name: String,
                       description: String,
                       default: Option[String] = None,
                       websiteUrl: String,
                       distribution: String = "UNIVERSAL")

  case class Version(candidate: String,
                     version: String,
                     url: String,
                     platform: Platform = Universal,
                     vendor: Option[Vendor] = None)

  def candidateToDocument(c: Candidate): Document = {
    val doc = buildCandidateDocument(c.candidate, c.name, c.description, c.websiteUrl, c.distribution)
    c.default.fold(doc) { default =>
      doc.append("default", default)
    }
  }

  private def buildCandidateDocument(candidate: String, name: String, description: String, websiteUrl: String, distribution: String) =
    new Document("candidate", candidate)
      .append("name", name)
      .append("description", description)
      .append("websiteUrl", websiteUrl)
      .append("distribution", distribution)

  def versionToDocument(cv: Version): Document = {
    val doc = buildVersionDocument(cv.candidate, cv.version, cv.platform.id, cv.url)
    cv.vendor.fold(doc) { vendor =>
      doc.append("vendor", vendor.id)
    }
  }

  private def buildVersionDocument(candidate: String, version: String, platform: String, url: String) =
    new Document("candidate", candidate)
      .append("version", version)
      .append("platform", platform)
      .append("url", url)

  def removeCandidate(candidate: String)(implicit db: MongoDatabase): Unit =
    db.getCollection(CandidatesCollection).deleteOne(new Document("candidate", candidate))

  def removeVersion(candidate: String, version: String, platform: Platform = Universal)(implicit db: MongoDatabase): Unit =
    db.getCollection(VersionsCollection).deleteOne(new Document("candidate", candidate).append("version", version).append("platform", platform.id))

  def removeAllVersions(candidate: String)(implicit db: MongoDatabase): Unit =
    db.getCollection(VersionsCollection).deleteMany(new Document("candidate", candidate))

  def setCandidateDefault(candidate: String, version: String)(implicit db: MongoDatabase): Document =
    db.getCollection(CandidatesCollection).findOneAndUpdate(Filters.eq("candidate", candidate), Updates.set("default", version))
}