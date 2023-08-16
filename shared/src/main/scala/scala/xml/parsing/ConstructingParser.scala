/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package xml
package parsing

import java.io.File
import scala.io.Source

object ConstructingParser {
  def fromFile(/*GRADIENT*/ reg: Reg^, inp: File^, preserveWS: Boolean): ConstructingParser^{inp} =
    new ConstructingParser(reg, Source.fromFile(inp), preserveWS).initialize

  def fromSource(/*GRADIENT*/ reg: Reg^, inp: Source^, preserveWS: Boolean): ConstructingParser^{inp} =
    new ConstructingParser(reg, inp, preserveWS).initialize
}

/**
 * An xml parser. parses XML and invokes callback methods of a MarkupHandler.
 * Don't forget to call next.ch on a freshly instantiated parser in order to
 * initialize it. If you get the parser from the object method, initialization
 * is already done for you.
 *
 * {{{
 * object parseFromURL {
 *   def main(args: Array[String]) {
 *     val url = args(0)
 *     val src = scala.io.Source.fromURL(url)
 *     val cpa = scala.xml.parsing.ConstructingParser.fromSource(src, false) // fromSource initializes automatically
 *     val doc = cpa.document()
 *
 *     // let's see what it is
 *     val ppr = new scala.xml.PrettyPrinter(80, 5)
 *     val ele = doc.docElem
 *     println("finished parsing")
 *     val out = ppr.format(ele)
 *     println(out)
 *   }
 * }
 * }}}
 */
class ConstructingParser(/*GRADIENT*/reg: Reg^, override val input: Source^, override val preserveWS: Boolean)
  extends ConstructingHandler(reg)
  with ExternalSources
  with MarkupParser(reg)
