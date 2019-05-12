package net.scalytica.test

import net.scalytica.kafka.wsproxy.session.Session

trait SessionOpResultValues {

  implicit def convertSessionOpResultToValuable[T](
      res: Session.SessionOpResult
  ): Valuable = new Valuable(res)

  class Valuable(res: Session.SessionOpResult) {

    def value: Session = {
      res.session
    }
  }
}
