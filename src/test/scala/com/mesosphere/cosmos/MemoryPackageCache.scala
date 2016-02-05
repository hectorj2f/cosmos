package com.mesosphere.cosmos

import com.netaporter.uri.dsl.stringToUri
import com.twitter.util.Future

import com.mesosphere.cosmos.model._

/** A package cache that stores all package information in memory. Useful for testing.
  *
  * @param packages the contents of the cache: package data indexed by package name
  */
final case class MemoryPackageCache(packages: Map[String, PackageFiles]) extends PackageCache {

  override def getPackageByPackageVersion(
    packageName: String,
    packageVersion: Option[String]
  ): Future[PackageFiles] = {
    Future.value {
      packages.get(packageName) match {
        case None => throw PackageNotFound(packageName)
        case Some(a) => a
      }
    }
  }

  override def getPackageByReleaseVersion(
    packageName: String,
    releaseVersion: String
  ): Future[PackageFiles] = {
    Future.value {
      packages.get(packageName) match {
        case None => throw PackageNotFound(packageName)
        case Some(a) => a
      }
    }
  }

  def getRepoIndex: Future[UniverseIndex] = {
    Future.exception(RepositoryNotFound("http://example.com/universe.zip"))
  }

  def getPackageIndex(
    packageName: String
  ): Future[PackageInfo] = {
    Future.exception(PackageNotFound(packageName))
  }

}
