import reactivemongo.bson._
import reactivemongo.api._, collections.bson._

object Test {
  def foo(coll: BSONCollection) = coll.find(BSONDocument.empty)
}
