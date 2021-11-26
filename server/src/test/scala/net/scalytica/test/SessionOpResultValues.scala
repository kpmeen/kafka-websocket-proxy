package net.scalytica.test

import net.scalytica.kafka.wsproxy.session._

trait SessionOpResultValues {

  implicit def convertSessionOpResultToValuable[T](
      res: SessionOpResult
  ): Valuable = new Valuable(res)

  class Valuable(res: SessionOpResult) {

    def value: Session = {
      res.session
    }
  }
}
