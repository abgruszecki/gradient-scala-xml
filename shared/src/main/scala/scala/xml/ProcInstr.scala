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

/**
 * an XML node for processing instructions (PI)
 *
 * @author Burak Emir
 * @param  target     target name of this PI
 * @param  proctext   text contained in this node, may not contain "?>"
 */
// Note: used by the Scala compiler.
case class ProcInstr(target: String, proctext: String) extends SpecialNode {
  if (!Utility.isName(target))
    throw new IllegalArgumentException(s"$target must be an XML Name")
  if (proctext.contains("?>"))
    throw new IllegalArgumentException(s"""$proctext may not contain "?>"""")
  if (target.toLowerCase == "xml")
    throw new IllegalArgumentException(s"$target is reserved")

  final override def doCollectNamespaces: Boolean = false
  final override def doTransform: Boolean = false

  final override def label: String = "#PI"
  override def text: String = ""

  /**
   * appends &quot;&lt;?&quot; target (&quot; &quot;+text)?+&quot;?&gt;&quot;
   *  to this stringbuffer.
   */
  override def buildString(sb: StringBuilder^): StringBuilder^{sb} = {
    val textStr: String = if (proctext == "") "" else s" $proctext"
    sb.append(s"<?$target$textStr?>")
  }
}
