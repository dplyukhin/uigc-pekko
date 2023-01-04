/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.cookbook

import java.util.UUID

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl._

class RecipeSourceFromFunction extends RecipeSpec {

  "A source that repeatedly evaluates a function" must {

    "be a mapping of Source.repeat" in {
      def builderFunction(): String = UUID.randomUUID.toString

      // #source-from-function
      val source = Source.repeat(NotUsed).map(_ => builderFunction())
      // #source-from-function

      val f = source.take(2).runWith(Sink.seq)
      f.futureValue.distinct.size should ===(2)
    }
  }
}