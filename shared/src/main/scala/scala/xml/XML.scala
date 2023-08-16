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

import factory.XMLLoader
import java.io.{File, FileDescriptor, FileInputStream, FileOutputStream, InputStream, Reader, StringReader, Writer}
import java.nio.channels.Channels
import scala.util.control.Exception

object Source^{fs} {
  def fromFile(name: String)^{fs}: InputSource^# = fromFile(new File(name)(/*GRADIENT*/fs))
  def fromFile(file: File^)^{}: InputSource^# = fromUrl(file.toURI.toURL)
  def fromUrl(url: java.net.URL^)^{}: InputSource^# = fromSysId(url.toString)
  def fromSysId(sysID: String)^{}: InputSource^# = enclose[{}] { #new InputSource(sysID) }
  def fromFile(fd: FileDescriptor^)^{}: InputSource^# = fromInputStream(new FileInputStream(fd))
  def fromInputStream(is: InputStream^)^{}: InputSource^# = enclose[{}] { #new InputSource(is) }
  def fromString(string: String)^{}: InputSource^# = fromReader(new StringReader(string))
  def fromReader(reader: Reader^)^{}: InputSource^# = enclose[{}] { #new InputSource(reader) }
}

/**
 * Governs how empty elements (i.e. those without child elements) should be serialized.
 */
object MinimizeMode extends Enumeration {
  /**
   * Minimize empty tags if they were originally empty when parsed, or if they were constructed
   *  with [[scala.xml.Elem]]`#minimizeEmpty` == true
   */
  val Default: Value = Value

  /**
   * Always minimize empty tags.  Note that this may be problematic for XHTML, in which
   * case [[scala.xml.Xhtml]]`#toXhtml` should be used instead.
   */
  val Always: Value = Value

  /**
   * Never minimize empty tags.
   */
  val Never: Value = Value
}

/**
 * The object `XML` provides constants, and functions to load
 *  and save XML elements. Use this when data binding is not desired, i.e.
 *  when XML is handled using `Symbol` nodes.
 *
 *  @author  Burak Emir
 */
object XML^{fs} extends XMLLoader[Elem] {
  val xml: String = "xml"
  val xmlns: String = "xmlns"
  val namespace: String = "http://www.w3.org/XML/1998/namespace"
  val preserve: String = "preserve"
  val space: String = "space"
  val lang: String = "lang"
  val encoding: String = "UTF-8"

  /** Returns an XMLLoader whose load* methods will use the supplied SAXParser. */
  def withSAXParser(p: SAXParser^#)(
    /*GRADIENT*/reg: Reg^
  )^{}: XMLLoader[Elem]^# { val reg: package.reg.type } =
    enclose[{}] { #new XMLLoader[Elem](reg) {
      override val parser: SAXParser = p
    }}

  /** Returns an XMLLoader whose load* methods will use the supplied XMLReader. */
  def withXMLReader(r: XMLReader^#)(
    /*GRADIENT*/reg: Reg^
  )^{}: XMLLoader[Elem]^# { val reg: package.reg.type } =
    enclose[{}] { #new XMLLoader[Elem](reg) {
      override val reader: XMLReader^# = r
    }}

  /**
   * Saves a node to a file with given filename using given encoding
   *  optionally with xmldecl and doctype declaration.
   *
   *  Note: Before scala-xml 1.1.0, the default encoding was ISO-8859-1 (latin1).
   *  If your code depends on characters in non-ASCII latin1 range, specify
   *  ISO-8859-1 encoding explicitly.
   *
   *  @param filename the filename
   *  @param node     the xml node we want to write
   *  @param enc      encoding to use
   *  @param xmlDecl  if true, write xml declaration
   *  @param doctype  if not null, write doctype declaration
   */
  final def save(
    filename: String,
    node: Node,
    enc: String = "UTF-8",
    xmlDecl: Boolean = false,
    doctype: dtd.DocType = null
  )^{fs}: Unit = {
    val fos: FileOutputStream^{fs} = new FileOutputStream(filename)(/*GRADIENT*/fs)
    val w: Writer^{fs} = Channels.newWriter(fos.getChannel, enc)

    Exception.ultimately(w.close())(
      write(w, node, enc, xmlDecl, doctype)
    )
  }

  /**
   * Writes the given node using writer, optionally with xml decl and doctype.
   *  It's the caller's responsibility to close the writer.
   *
   *  @param w        the writer
   *  @param node     the xml node we want to write
   *  @param enc      the string to be used in `xmlDecl`
   *  @param xmlDecl  if true, write xml declaration
   *  @param doctype  if not null, write doctype declaration
   */
  final def write(
    w: Writer^,
    node: Node,
    enc: String,
    xmlDecl: Boolean,
    doctype: dtd.DocType,
    minimizeTags: MinimizeMode.Value = MinimizeMode.Default
  )^{}: Unit = {
    /* TODO: optimize by giving writer parameter to toXML*/
    if (xmlDecl) w.write(s"<?xml version='1.0' encoding='$enc'?>\n")
    if (doctype.ne(null)) w.write(s"$doctype\n")
    w.write(Utility.serialize(node, minimizeTags = minimizeTags).toString)
  }
}
