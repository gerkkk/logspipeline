package lib

import java.io.File
import scala.collection.mutable.ArrayBuffer

object FileDiscovery {

  def listNumericFiles(rootPath: String): Seq[String] = {
    val root = new File(rootPath)
    require(root.exists() && root.isDirectory, s"Input directory not found: $rootPath")

    val buffer = ArrayBuffer.empty[String]
    collectNumericFiles(root, buffer)
    buffer.sorted(Ordering.String).toSeq
  }

  private def collectNumericFiles(dir: File, buffer: ArrayBuffer[String]): Unit = {
    val children = Option(dir.listFiles()).getOrElse(Array.empty)
    children.foreach { child =>
      if (child.isDirectory) {
        collectNumericFiles(child, buffer)
      } else if (LogPatterns.NumericFileName.matches(child.getName)) {
        buffer += child.getAbsolutePath
      }
    }
  }
}
