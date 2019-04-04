package net.scalytica.kafka.wsproxy

import akka.NotUsed
import akka.stream.scaladsl.Source

package object models {

  implicit def seqToSource[Out](s: Seq[Out]): Source[Out, NotUsed] = {
    val it = new scala.collection.immutable.Iterable[Out] {
      override def iterator: Iterator[Out] = s.toIterator
    }
    Source(it)
  }

}
