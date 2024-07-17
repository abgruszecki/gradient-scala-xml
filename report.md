# Migration report

The `scala-xml` library has c. 4.2k lines of code (excluding comments). Adding capture annotations required modifying 260 lines. The library was updated without any need to refactor the code. The majority of these changes (c. 200) were just a few characters; they required capture-annotating a function that takes a mutable argument. For instance, the following signature:

```scala
def buildString(sb: StringBuilder): StringBuilder
```

had to be updated to:

```scala
def buildString(sb: StringBuilder^): StringBuilder^{sb}
```

In 24 cases, a local region had to be added in order to temporarily allocate mutable objects. Deciding if this was possible involved inspecting if the allocated mutable object leaves local scope. For the purposes of illustration, we created the local regions explicitly. While doing so manually was simple, in a practical implementation the regions would be implicitly inserted by the compiler, based on an escape analysis.

14 classes had to be updated to take a region as an argument, since they allocated mutable objects as part of their API. Most of these classes were related to parsing. 2 were mutable collections; 2 were classes representing XML data. It was always clear when to update a class in such a manner: either when it allocated mutable objects and exposed them through its API, or when the class extended other allocation-polymorphic classes.

Type occurences of each region-polymorphic class had to be refined. For instance, the `DTD` class takes `reg: Reg^` as an argument. The type of `new DTD(reg0)` should not be merely rewritten from `DTD` to `DTD^{reg0}`, we also need to add a refinement: `DTD^{reg0} { val reg: reg0.type }` since we need to know that the `reg` field of `DTD` stores the *same capability* as `reg0`; for a more detailed explanation see an aside at the end of the report. For the purposes of illustration we consistently updated occurences of all such classes to be refined, even if not doing so would still allow the `scala-xml` library to compile. In a practical implementation of Gradient, region-polymorphic classes would have better support. By analogy to type arguments, we should be able to write `DTD(reg0)` instead of the significantly more verbose `DTD^{reg0} { val reg: reg0.type }`. Likewise, mentioning `DTD` as a (first-order) type without specifying its argument would be a compile-time error. If the argument was unknown, we would be forced to write a wildcard `DTD(_)`.

As predicted, 3 classes implementing the XML parser captured the `fs` and `net` devices. Concretely, `scala.xml.parsing.ExternalSources` defines an `externalSource` method which constructs instances of `scala.io.Source`. Since these objects allow accessing the filesystem and the network, creating them requires passing either `fs` or `net` (or both) as an argument to the `Source`. There are two other classes which extend `ExternalSources`. The easiest way to migrate a class like `ExternalSources` is to assume all of its instances capture both `fs` and `net`. To state that explicitly, we extended the class syntax with a `capt C` declaration, where `C` is a capture set. We explain the details of how to understand classes using our formalism in a following section.

Finally, in order to make the migration meaningful, we removed a vestigial implementation of finite automatons from the library. The implementation allowed represent regexes as finite automatons and nothing else. It was deprecated and unused anywhere in the `scala-xml` library: the library compiles and passes all tests after the removal. It relied on unnecessary mutable state, some of which was actually kept in the instances of the `RegEx` class. We removed the code in a separate commit, since keeping it would unrealistically increase the number of capture annotations as keeping the mutable state in `RegEx` would make a drastically higher number of classes (transitively) mutable and therefore tracked.

# Methodology for migrating the library

In order to migrate the `scala-xml` library to Gradient, we first carefully inspected the entire source code.

-   We listed all the mutable classes available without an import, all of which require a capture annotation. (These include `Array`, `StringBuffer`, `Iterator`.)
-   All the mutable state in the file had to be accounted for:
    -   Each element of mutable state has to be inspected to verify whether it is local or is returned to an outside context. There were no occurences of a local `var` that were captured by a closure and returned outside of the `var`'s lexical scope.
    -   Each class had to be inspected to verify if it is pure or not: classes with mutable state are impure and therefore tracked. We listed all impure classes.
-   For each file, we inspected its imports looking for classes that allow access to restricted functionality (such as `scala.io.Source`, `java.io.File` and `java.io.InputStream`). All their occurences inside of the file had to be inspected and accounted for.
-   We listed every class which was mutable, region-polymorphic, or marked (because it extended a Java interface).

After inspecting each file, we then searched the codebase for text occurences of every significant class in the above lists, to verify that we did not miss any occurence and that all the mutability and region-polymorphism information was propagated.

Every change which involved more than just adding a capture annotation starts with a marker comment: `/*GRADIENT*/`.

# Understanding Scala features in terms of GradCC

We now explain the details of how to understand the surface Scala features in terms of the formalism.

## Understanding packages

In Scala, a package is declared with a top-level `package` statement, whose syntax is quite flexible. For the purposes of Gradient, we make a few simplifying assumptions, which (according to our Scala experience) are in practice almost universally obeyed.

As we explained in the paper, we understand each package as a a first-class object. We understand Scala packages to have a flat structure, e.g. package `scala.xml` is *not* a member of the `scala` package. For the purposes of capture tracking, the hierarchical names are just that: names.

Second, we understand each package to be defined by a *single* library. The library may spread each package's members over multiple files. Essentially, it is as though all the files were aggregated together into a single object definition before being compiled.

These two assumptions mean that we can treat a complete Scala program as a single long file, starting with all the packages and modules collected from its dependencies, which are followed by the program itself.

### How do packages interact with capture sets?

It is primarily the capture sets of a package's members which are significant. The packages technically are objects and therefore have their own capture sets, but no definition captures the entire package. For instance, if the `main` method in the `main` module refers to `scala.xml.XML.fromString`, then it only captures `scala.xml.XML.fromString`. If the `main` method captures nothing else, then its capture set will be empty, since the capture set of `scala.xml.XML.fromString` itself is empty even if the `scala.xml.XML` object (and indirectly, the `scala.xml` package) capture devices and have non-empty capture sets.

To provide a detailed illustration, let's take the following package:

```scala
package xml {
  def parseFile(filepath: String)^{fs}: Node = ...
  def parseString(str: String)^{}: Node = ...
}
```

One case where capture sets really matter is an enclosure. If the capture sets tell us statically an enclosure's restriction may be disobeyed, we want to have a compile-time error, i.e. an ill-typed term. Then: can we run `xml.parseString` under `enclosed[{}]`, an enclosure which dynamically enforces no devices are accessed? Yes we can! The only capture set that matters in that case is that of `xml.parseString`, not those of `xml.parseFile` or of `xml` itself. To illustrate further, the formalism allows us to accept the following code snippet:

```scala
package foo {
  import xml

  def readDummyConfig(): Node =
    xml.parseString("<cfg/>")

  def enclosedReadDummyConfig(): Node =
    // the enclosure forbids reading any devices
    enclosed[{}] {
      this.readDummyConfig()
    }
}

package bar {
  import foo

  def indirectEnclosedReadDummyConfig(): Node =
    enclosed[{}] {
      foo.readDummyConfig()
    }
}
```

The snippet above corresponds to the following formal term (written with Scala-like syntax):

```scala
// `type`-s are simple aliases for terseness
type Xml = ImpureModule {
  val parseFile: (filepath: String) ->{fs} Node
  val parseString: (str: String) ->{} Node
}
val xml: Xml = {
  val tmp = new {
    val new_xml: (fs: Fs^) -> Xml^ =
      (fs: Fs^) => new {
        val parseFile = (filepath: String) => ...
        val parseString = (str: String) => ...
      }
  }
  tmp.new_xml(fs)
}


type Foo(xml: Xml) = ImpureModule {
  val readDummyConfig: (u: Unit) ->{xml.parseString} Node
  val enclosedReadDummyConfig: (u: Unit) ->{} Node
}
val foo: Foo(xml) = {
  val tmp = new {
    val new_foo: (xml: Xml) -> Foo^ =
      (xml: Xml) => new {
        val readDummyConfig = (u: Unit) => ...
        val enclosedReadDummyConfig = (u: Unit) => ...
      }
  }
  tmp.new_foo(xml)
}


type bar(xml: Xml, foo: Foo(xml)) = ImpureModule {
  val indirectEnclosedReadDummyConfig: (u: Unit) ->{} Node
}
val bar: Bar(xml, foo)^ = {
  val tmp = new {
    val new_bar: (foo: Foo(xml)) -> Bar(xml, foo)^ =
      (foo: Foo(xml)) => new {
        val indirectEnclosedReadDummyConfig = (u: Unit) => ...
      }
  }
}

```

### Understanding packages to modules

Like mentioned in the paper, packages are a special sort of module.

Here is how we can understand them in a way congruent with the formalism. First, assume we have the following complete program:

```scala
type Fs = ...
val fs: Fs^ = ...

package scala_xml {
  def parseXml(filepath: String): Node =
    val stream = fs.openInputStream(filepath)
  ...
}

module main(package scala_xml) {
  def main(): Unit =
    scala_xml.parseXml(...)
}
```

The program defines the `scala.xml` package. On the surface syntax level, the package acts as a first-class object that has captured the `fs` devices. In the capture set of the package (written explicitly), we can see the captured devices. The `main` module has no ambient authority and cannot access `scala.xml` directly; it needs to explicitly request it as a dependency.

This program is translated (using the code and the types) to the following:

```scala
type Fs = ...
val fs: Fs^ = ...

module scala_xml(device fs) {
  def parseXml(filepath: String): Node =
    val stream = fs.openInputStream(filepath)
  ...
}

// Note: scala_xml here refers to the type of instances of the scala_xml module
module main(my_scala_xml: scala_xml^) {
  def main(): Unit =
    my_scala_xml.parseXml(...)
}

// Platform code: initialize and run the main module
val my_scala_xml = scala_xml(fs)
val my_main = main(my_scala_xml)
my_main.run()
```

The `scala.xml` package was translated to a module; accordingly, the `main` module now takes an instance of `scala-xml` as an argument. In addition to the module definitions, we now have an automatically-generated top-level term which initializes the `main` module. This term also initializes all the packages. We see that a package can be understood as a special module that chooses what devices it captures.

The translation from the above snippet to a formal term is now straightforward.

## Understanding classes

Classes can also be understood based on the formalism presented in the paper. (We are using Scala terminology: by "classes" we mean both *proper* classes, defined with the `class` keyword, and traits.)

An instance of a class is simply a record.

A capture-checked class may extend a Java class. If the Java class is marked, then so is the class that extends it.

The capture sets of methods are by default assumed to be `{this}`, unless specified explicitly.

A class may explicitly state it captures some capabilities from the context that defines it by beginning its body with the `capt C` statement, where `C` is a capture set. There are two classes which do so in `scala-xml`: `ExternalSources` and `MarkupParser`. The former features a `capt {net,fs}` statement, since its method `externalSource` captures these capabilities. Likewise, multiple methods of `MarkupParser` access `net` and `fs`.

### Understanding traits and constructors

A *proper* class corresponds to (1) a type and (2) a constructor, which need to be carefully distinguished. The constructor is a method, which comes with its own capture set. (This capture set is never annotated in the sources.) Scala syntax allows distributing the primary constructor of a Scala class over its body. For instance, the Scala source allows defining the following class

```scala
class DTD(reg: Reg^) {
  var externalID: ExternalID = _
  var decls: List[Decl] = Nil

  var elem: mutable.Map[String, ElemDecl]^{reg} = reg.new mutable.HashMap[String, ElemDecl]()
  var attr: mutable.Map[String, AttListDecl]^{reg} = reg.new mutable.HashMap[String, AttListDecl]()
  var ent: mutable.Map[String, EntityDecl]^{reg} = reg.new mutable.HashMap[String, EntityDecl]()

  def notations: Seq[NotationDecl] = Nil
  def unparsedEntities: Seq[EntityDecl] = Nil

  override def toString: String =
    s"DTD ${Option(externalID).getOrElse("")} [\n${decls.mkString("\n")}\n]"
}
```

which should be understood to correspond to the following definitions:

```scala
def newDTD(reg: Reg^) = {
  val res = new DTD(reg)
  res.decls = Nil
  res.elem = reg.new mutable.HashMap[String, ElemDecl]()
  res.attr = reg.new mutable.HashMap[String, AttListDecl]()
  res.ent = reg.new mutable.HashMap[String, EntityDecl]()
  return res
}

class DTD(reg: Reg^) {
  var externalID: ExternalID
  var decls: List[Decl]
  var elem: mutable.Map[String, ElemDecl]^{reg}
  var attr: mutable.Map[String, AttListDecl]^{reg}
  var ent: mutable.Map[String, EntityDecl]^{reg}

  def notations: Seq[NotationDecl] = Nil
  def unparsedEntities: Seq[EntityDecl] = Nil

  override def toString: String =
    s"DTD ${Option(externalID).getOrElse("")} [\n${decls.mkString("\n")}\n]"
}
```

Every occurences of `new DTD` in the surface Scala is to be understood as a call to the desugared `newDTD`. A desugared class can only define `val` members in its constructor, not in its body; if necessary, this desugaring may add extra arguments to the class' desugared constructor.

As opposed to a class, a trait corresponds to (1) a type and (2) a *constructor mixin*. A trait cannot be instatiated on its own, it has to first be extended by a class (the class may be anonymous). The constructor mixin becomes part of the constructor of each class extending the trait. This mixin *may* make the end constructor capture more.

All class members, including `def`-s, have a capture set. In terms of the formalism, a `def` corresponds to a field holding a closure and a `var` corresponds to a field holding a mutable reference. The capture sets of `var`-s and `val`-s need to be stated explicitly; the capture sets of `def`-s are by default assumed to be `{this}` unless stated otherwise. The formalism explains how to typecheck a class with all of its traits mixed in: at that point we have a lambda that creates a non-module object, and the typing rules for non-module objects easily follow from those for modules.

## Understanding Java classes

We distinguish between two kinds of Java classes, depending on whether they are from the standard library. We assume that the compiler has special support for standard Java classes: their captures are *asserted*. For instance, instances of `String` (which in Scala is an alias for `java.lang.String`!) are pure. while allocating an instance of `java.io.File` takes and captures the `fs` device. To be clear, `File` *does not* call methods on the device, it accesses system calls directly; taking `fs` as an argument is a type-system fiction to track what syscalls may be invoked. This is analogous to the compiler asserting the types of native operations like integer addition. Note that `File` needs the exact `fs` device as an argument, not just an object of type `Fs^`.

If a Java class is defined outside of the standard library, it is treated as capture-unchecked code: it is always marked and its fields and methods can only be accessed under an enclosure. Furthermore, every object it returns is marked as well, with the exception of capture-checked final classes. For instance, if foreign Java code from library `L` returns a `String`, we know the returned `String` is pure because it is final: it was created via the code in the Java standard library. However, if such code returns an instance of `File`, this instance could actually be a subclass of `File` defined inside `L`. Since this subclass was not capture-checked by Gradient, it can disobey the capture signatures asserted for `File`, and therefore we need to brand this particular instance instead of relying on the asserted capture sets.

## Understanding expressions

### Local mutable state

The body of a method may contain local `var`-s. These should be understood as `Ref`-s allocated on a local region. Both the region and the references are created every time the body is evaluated. Technically, the references (and therefore the region) could be captured by objects escaping from the local scope, which would affect the capture sets of these objects. Such capture never occurs in the `scala-xml` library.

### Pattern matching and type casts

The capture sets of pattern-bound variables are assigned based on the type of the scrutinee; pattern matching cannot be used to circumvent capture checking.

Type casts need to be inspected to verify they do not circumvent capture tracking, i.e., that they are not used to remove capabilities from capture sets. None of the casts in the `scala-xml` library do so.

# Verifying the migrated library

In order to facilitate verifying how we migrated `scala-xml`, we include a list of imports which require special treatment, as well as a list of all mutable, marked and region-polymorphic classes, to help with locating them and verifying they are correctly migrated.

Significant types and packages:

-   defined in the `scala` package:
    -   `StringBuilder`
    -   `Iterator` and `.iterator`
    -   `Source`
    -   `Array`
-   aliases defined in the `scala-xml` package object:
    -   `SAXException`
    -   `SAXParseException`
    -   `EntityResolver`
    -   `InputSource`
    -   `XMLReader`
    -   `SAXParser`
-   other:
    -   `scala.collection.mutable`
    -   `java.io`

## Class list

-   `NodeBuffer.scala`: mut
-   `NodeSeq.scala`
-   `TypeSymbol.scala`
-   `Node.scala`
-   `SpecialNode.scala`
-   `Equality.scala`
-   `NamespaceBinding.scala`
-   `MetaData.scala`
-   `TokenTests.scala`
-   `Utility.scala`
    -   `collectNamespaces`: takes `Reg`
    -   `parseAttributeValue`: returns a MUTABLE `Seq`
        -   unused in the library
-   `Atom.scala`
-   `Attribute.scala`
-   `Comment.scala`
-   `Document.scala`: mut, reg-poly
-   `Elem.scala`
-   `EntityRef.scala`
-   `Group.scala`
-   `MalformedAttributeException.scala`
-   `Null.scala`
-   `PCData.scala`
-   `PrefixedAttribute.scala`
-   `PrettyPrinter.scala`: mut
-   `ProcInstr.scala`
-   `QNode.scala`
-   `Text.scala`
-   `TextBuffer.scala`: mut, reg-poly
    -   does not need to be refined, nothing in the codebase accesses `.sb`
-   `TopScope.scala`
-   `Unparsed.scala`
-   `UnprefixedAttribute.scala`
-   `package.scala`
-   `dtd/`
    -   `dtd/impl/Base.scala`
    -   `dtd/impl/WordExp.scala`
    -   `dtd/impl/SyntaxError.scala`
    -   `dtd/impl/BaseBerrySethi.scala`: removed
    -   `dtd/impl/DetWordAutom.scala`: removed
    -   `dtd/impl/Inclusion.scala`: removed
    -   `dtd/impl/NondetWordAutom.scala`: removed
    -   `dtd/impl/SubsetConstruction.scala`: removed
    -   `dtd/impl/WordBerrySethi.scala`: removed
    -   `dtd/DocType.scala`
    -   `dtd/ExternalID.scala`
    -   `dtd/Tokens.scala`
    -   `dtd/ValidationException.scala`
    -   `dtd/Decl.scala`
    -   `dtd/ContentModel.scala`
    -   `dtd/DTD.scala`: mut, reg-poly
-   `include/`
    -   `include/sax/EncodingHeuristics.scala`
    -   `include/sax/XIncludeFilter.scala`: marked
        -   extends a Java class
    -   `include/sax/XIncluder.scala`: marked
        -   extends a Java class
-   `transform/`
    -   `transform/BasicTransformer.scala`
    -   `transform/RewriteRule.scala`
    -   `transform/NestingTransformer.scala`
    -   `transform/RuleTransformer.scala`
-   `factory/`
    -   `factory/NodeFactory.scala`: reg-poly
    -   `factory/XMLLoader.scala`: reg-poly
-   `parsing/`
    -   `parsing/DtdBuilder.scala`: mut, reg-poly
    -   `parsing/ElementContentModel`
    -   `parsing/ExternalSources.scala`: capt
    -   `parsing/FactoryAdapter.scala`: mut, marked, reg-poly
    -   `parsing/NoBindingFactoryAdapter.scala`: reg-poly
    -   `parsing/FatalError.scala`
    -   `parsing/MarkupHandler.scala`: mut, reg-poly
        -   uses a pure Source
    -   `parsing/DefaultMarkupHandler.scala`: mut
    -   `parsing/MarkupParser.scala`: mut, reg-poly, capt
    -   `parsing/MarkupParserCommon.scala`: mut, reg-poly
    -   `parsing/TokenTests.scala`
    -   `parsing/ConstructingHandler.scala`: mut, reg-poly
    -   `parsing/ConstructingParser.scala`: mut, reg-poly
    -   `parsing/XhtmlEntities.scala`
    -   `parsing/XhtmlParser.scala`: mut, reg-poly
-   `XML.scala`

# Asides

## Why do allocation-polymorphic class need a refinement?

The `DTD` class takes `reg: Reg^` as an argument and defines the following member:

```scala
var ent: mutable.Map[String, EntityDecl]^{reg}
```

Consider the following illustratory snippet:

```scala
val reg0: Reg^ = ...
var ent0: mutable.Map[String, EntityDecl]^{reg0} = null
val dtd: DTD^{reg0} = new DTD(reg0)
ent0 = dtd.ent
```

We define a mutable variable `ent0`. Its capture set needs to be explicitly specified. The type of `dtd.ent` is `mutable.Map[String, EntityDecl]^{dtd.reg}`. This type is not compatible with the type of `ent0`: we do not know that `dtd.reg` and `reg0` are the exact same object. In order to do so, we need to refine the type of `dtd` and refine the type of `dtd.reg` using a *singleton type*:

```scala
val dtd: DTD^{reg0} { val reg: reg0.type } = new DTD(reg0)
```
