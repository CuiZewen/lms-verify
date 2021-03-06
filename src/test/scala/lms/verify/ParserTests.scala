package lms.verify

// inspired by http://manojo.github.io/2015/09/04/staged-parser-combinators-recursion

trait StagedParser extends Dsl with Reader {
  // Parser Result
  abstract class ParseResultCPS[T: Typ] { self =>

    def apply[X: Typ](
      success: (Rep[T], Rep[Input]) => Rep[X],
      failure: Rep[Input] => Rep[X]
    ): Rep[X]

    def map[U: Typ](f: Rep[T] => Rep[U]) = new ParseResultCPS[U] {
      def apply[X: Typ](
        success: (Rep[U], Rep[Input]) => Rep[X],
        failure: Rep[Input] => Rep[X]
      ): Rep[X] = self.apply(
        (t: Rep[T], in: Rep[Input]) => success(f(t), in),
        failure
      )
    }

    def flatMapWithNext[U: Typ](f: (Rep[T], Rep[Input]) => ParseResultCPS[U])
        = new ParseResultCPS[U] {

      def apply[X: Typ](
        success: (Rep[U], Rep[Input]) => Rep[X],
        failure: Rep[Input] => Rep[X]
      ): Rep[X] = {
        var isEmpty = unit(true); var value = zeroVal[T]; var rdr = zeroVal[Input]

        self.apply[Unit](
          (x, next) => { isEmpty = unit(false); value = x; rdr = next },
          next => rdr = next
        )

        if (isEmpty) failure(rdr) else f(value, rdr).apply(success, failure)
      }
    }

    def orElse(that: => ParseResultCPS[T]) = new ParseResultCPS[T] {
      def apply[X: Typ](
        success: (Rep[T], Rep[Input]) => Rep[X],
        failure: Rep[Input] => Rep[X]
      ): Rep[X] = {
        var isEmpty = unit(true); var value = zeroVal[T]; var rdr = zeroVal[Input]

        self.apply[Unit](
          (x, next) => { isEmpty = unit(false); value = x; rdr = next },
          next => ()
        )

        if (isEmpty) that.apply(success, failure) else success(value, rdr)
      }
    }

    def toResult(default: Rep[T]): Rep[T] = {
      var value = default
      self.apply(
        (t, _) => value = t,
        _ => unit(())
      )
      value
    }
  }

  case class ParseResultCPSCond[T: Typ](
    cond: Rep[Boolean],
    t: ParseResultCPS[T],
    e: ParseResultCPS[T]
  ) extends ParseResultCPS[T] { self =>

    def apply[X: Typ](
      success: (Rep[T], Rep[Input]) => Rep[X],
      failure: Rep[Input] => Rep[X]
    ): Rep[X] = if (cond) t(success, failure) else e(success, failure)


    override def map[U: Typ](f: Rep[T] => Rep[U]) = new ParseResultCPS[U] {
      def apply[X: Typ](
        success: (Rep[U], Rep[Input]) => Rep[X],
        failure: Rep[Input] => Rep[X]
      ): Rep[X] = {
        var isEmpty = unit(true); var value = zeroVal[T]; var rdr = zeroVal[Input]

        self.apply[Unit](
          (x, next) => { isEmpty = unit(false); value = x; rdr = next },
          next => rdr = next
        )

        if (isEmpty) failure(rdr) else success(f(value), rdr)
      }
    }


    override def orElse(that: => ParseResultCPS[T]): ParseResultCPS[T] = {
      var isEmpty = unit(true); var value = zeroVal[T]; var rdr = zeroVal[Input]

      self.apply[Unit](
        (x, next) => { isEmpty = unit(false); value = x; rdr = next },
        next => ()
      )

      conditional(isEmpty, that, ParseResultCPS.Success(value, rdr))
    }
  }

  object ParseResultCPS {
    def Success[T: Typ](t: Rep[T], next: Rep[Input]) = new ParseResultCPS[T] {
      def apply[X: Typ](
        success: (Rep[T], Rep[Input]) => Rep[X],
        failure: (Rep[Input]) => Rep[X]
      ): Rep[X] = success(t, next)
    }

    def Failure[T: Typ](next: Rep[Input]) = new ParseResultCPS[T] {
      def apply[X: Typ](
        success: (Rep[T], Rep[Input]) => Rep[X],
        failure: (Rep[Input]) => Rep[X]
      ): Rep[X] = failure(next)
    }
  }

  def conditional[T: Typ](
    cond: Rep[Boolean],
    thenp: => ParseResultCPS[T],
    elsep: => ParseResultCPS[T]
  ): ParseResultCPS[T] = ParseResultCPSCond(cond, thenp, elsep)

  // Parser
  abstract class Parser[T: Typ]
      extends (Rep[Input] => ParseResultCPS[T]) {

    private def flatMap[U: Typ](f: Rep[T] => Parser[U]) = Parser[U] { input =>
      this(input) flatMapWithNext { (res, rdr) => f(res)(rdr) }
    }

    def >>[U: Typ](f: Rep[T] => Parser[U]) = flatMap(f)

    def ~[U: Typ](that: => Parser[U]): Parser[(T, U)] =
      for (l <- this; r <- that) yield make_tuple2(l, r)

    def ~>[U: Typ](that: => Parser[U]): Parser[U] =
      this flatMap { l => that }

    def <~[U: Typ](that: => Parser[U]): Parser[T] =
      for (l <- this; r <- that) yield l

    def map[U: Typ](f: Rep[T] => Rep[U]) = Parser[U] { input =>
      this(input) map f
    }

    def ^^[U: Typ](f: Rep[T] => Rep[U]) = map(f)

    def ^^^[U: Typ](u: Rep[U]) = map(x => u)

    def | (that: => Parser[T]) = Parser[T] { input =>
      this(input) orElse that(input)
    }
  }

  def __ifThenElse[A: Typ](
    cond: Rep[Boolean],
    thenp: => Parser[A],
    elsep: => Parser[A]
  ): Parser[A] = Parser[A] { input => conditional(cond, thenp(input), elsep(input)) }

  object Parser {
    def apply[T: Typ](f: Rep[Input] => ParseResultCPS[T]) = new Parser[T] {
      def apply(in: Rep[Input]) = f(in)
    }

    def phrase[T: Typ](p: => Parser[T], in: Rep[Input], default: Rep[T]): Rep[T] =
      p(in).toResult(default)
  }

  // CharParsers
  def acceptIf(p: Rep[Elem] => Rep[Boolean]) = Parser[Elem] { in =>
    conditional(
      in.atEnd,
      ParseResultCPS.Failure[Elem](in),
      conditional(
        p(in.first),
        ParseResultCPS.Success(in.first, in.rest),
        ParseResultCPS.Failure[Elem](in)
      )
    )
  }

  def accept(e: Rep[Elem]): Parser[Elem] = acceptIf(_ == e)

  def isLetter(c: Rep[Char]): Rep[Boolean] =
    (c >= unit('a') && c <= unit('z')) ||
    (c >= unit('A') && c <= unit('Z'))

  def letter: Parser[Char] = acceptIf(isLetter)

  def isDigit(c: Rep[Char]): Rep[Boolean] =
    c >= unit('0') && c <= unit('9')

  def digit: Parser[Char] = acceptIf(isDigit)
  def digit2Int: Parser[Int] = digit map (c => (c - unit('0')).asInstanceOf[Rep[Int]])

  def rep[T: Typ, R: Typ](p: Parser[T], z: Rep[R], f: (Rep[R], Rep[T]) => Rep[R], pz: Option[Rep[R] => Rep[Boolean]] = None) = Parser[R] { input =>
    var in = input
    var c = unit(true); var a = z
    loop (valid_input(in) && (pz.map(_(a)).getOrElse(true)), List[Any](in, c, a), 0) {
    while (c) {
      p(in).apply[Unit](
        (x, next) => { a = f(a, x); in = next },
        next => { c = false })
    }}
    ParseResultCPS.Success(a, in)
  }

  def accept(cs: List[Char]): Parser[Unit] = cs match {
    case Nil => Parser { i => ParseResultCPS.Success((), i) }
    case x :: xs => accept(x) ~> accept(xs)
  }
  def accept(s: String): Parser[Unit] = accept(s.toList)

/*

Alternative definition of `accept`. Generates less code, but arguably
more low-level.

  def accept(s: String) = Parser[Unit] { input =>
    var in = input
    var ok = unit(true)
    for (i <- (0 until s.length):Range) {
      if (ok) {
        if (in.atEnd) ok = false
        else if (in.first != s(i)) ok = false
        else in = in.rest
      }
    }
    conditional(
      ok,
      ParseResultCPS.Success((), in),
      ParseResultCPS.Failure(input))
  }
*/

  def repUnit[T: Typ](p: Parser[T]) =
    rep(p, (), { (a: Rep[Unit], x: Rep[T]) => a })

  def repN[T: Typ](p: Parser[T], n: Rep[Int]) = Parser[Unit] { input =>
    var ok = unit(true)
    var in = input
    loop(
      { i: Rep[Int] => 0<=i && valid_input(in) },
      { i: Rep[Int] => List(i, ok, in) },
      { i: Rep[Int] => n-i }) {
    for (i <- 0 until n)
      if (ok)
        p(in).apply[Unit](
          (_, next) => { in = next },
          next => { ok = false })
    }
    conditional(ok, ParseResultCPS.Success((), in), ParseResultCPS.Failure(input))
  }
}

// toy HTTP parser inspired by http://dl.acm.org/authorize?N80783
// also see https://github.com/manojo/experiments/blob/simple/src/main/scala/lms/parsing/examples/HttpParser.scala
//
// to avoid dealing with data structures, just returns
//   the content length of the payload if parse successful
//   -1 otherwise
trait HttpParser extends StagedParser {  import Parser._
  val OVERFLOW = -1
  def overflowOrPos: Option[Rep[Int] => Rep[Boolean]] =
    Some({ a: Rep[Int] => (a == OVERFLOW) || (0 <= a) })
  def numAcc(b: Int) = { (a: Rep[Int], x: Rep[Int]) =>
    if (a<0) a
    else if (a>Int.MaxValue / b - b) OVERFLOW
    else a*b+x
  }
  def num(c: Parser[Int], b: Int): Parser[Int] =
    c >> { z: Rep[Int] =>
      rep(c, z, numAcc(b), overflowOrPos)
    }

  def nat: Parser[Int] = num(digit2Int, 10)
  def acceptNat: Parser[Unit] =
    digit >> { z: Rep[Char] => repUnit(digit) }
  def ignoredNat: Parser[Unit] = acceptNat

  def anyChar: Parser[Char] = acceptIf(c => true)
  def wildChar: Parser[Char] = acceptIf(c => c != '\r')
  def acceptNewline: Parser[Unit] = accept("\r\n") ^^^ unit(())
  def acceptLine: Parser[Unit] = repUnit(wildChar) ~> acceptNewline
  def whitespaces: Parser[Unit] = repUnit(accept(' '))

  def status: Parser[Int] =
    (accept("HTTP/") ~> ignoredNat ~> accept('.') ~> ignoredNat  ~> whitespaces) ~>
    nat <~ acceptLine

  val CONTENT_LENGTH = 1
  def headerMap: List[(String, Int)] = ("Content-Length", CONTENT_LENGTH)::Nil
  val OTHER_HEADER = 0
  def headerName: Parser[Int] =
    ((for ((h,i) <- headerMap) yield (accept(h) ^^^ i)).reduce(_ | _)) |
    //(repUnit(letter | accept('-') | accept('$')) ^^^ OTHER_HEADER)
    (repUnit(acceptIf{c => c != ':' && c != ' '}) ^^^ OTHER_HEADER)

  val NO_VALUE = -2
  def headerValue(h: Rep[Int]) =
    if (h==CONTENT_LENGTH) (nat <~ whitespaces <~ acceptNewline)
    else (acceptLine ^^^ NO_VALUE)

  def header: Parser[Int] =
    (headerName <~ whitespaces <~ accept(':') <~ whitespaces) >> headerValue

  def headers: Parser[Int] =
    rep(header, 0, { (a: Rep[Int], x: Rep[Int]) => if (x==NO_VALUE) a else x })

  def acceptBody(n: Rep[Int]): Parser[Int] =
    if (n<0) Parser[Int] { input => ParseResultCPS.Failure(input) }
    else (repN(anyChar, n) ^^^ n)// <~ acceptNewline

  def http: Parser[Int] =
    (status ~> headers <~ acceptNewline) >> acceptBody

  def top = toplevel("p",
    { in: Rep[Input] =>
      var r = unit(-1)
      http(in).apply(
        (v, next) => if (next.atEnd) r = v,
        _ => unit(()))
      r: Rep[Int]
    },
    { in: Rep[Input] => valid_input(in) },
    { in: Rep[Input] => result: Rep[Int] => unit(true) })
}

trait ChunkedHttpParser extends HttpParser { import Parser._
  val TRANSFER_ENCODING = 2
  override def headerMap = super.headerMap :+ ("Transfer-Encoding", TRANSFER_ENCODING)

  val CHUNKED = -3
  override def headerValue(h: Rep[Int]) =
    if (h==TRANSFER_ENCODING) (accept("chunked") ^^^ CHUNKED) <~ whitespaces <~ acceptNewline
    else super.headerValue(h)

  override def acceptBody(n: Rep[Int]): Parser[Int] =
    if (n==CHUNKED) chunkedBody else super.acceptBody(n)

  def hexDigit2Int: Parser[Int] =
    digit2Int |
    (acceptIf(c => c >= unit('a') && c <= unit('f')) ^^
      (c => 10+(c - unit('a')).asInstanceOf[Rep[Int]]))
  def hex: Parser[Int] = num(hexDigit2Int, 16)

  def acceptChunk: Parser[Int] =
    (hex <~ acceptNewline) >> super.acceptBody

  def chunkedBody: Parser[Int] =
    rep(acceptChunk, 0, { (a: Rep[Int], x: Rep[Int]) =>
      if (a<0) a
      else if (a>Int.MaxValue - x) OVERFLOW
      else a+x
    }, overflowOrPos)
}

trait ToplevelAcceptParser extends StagedParser { import Parser._
  type ToplevelParser = Rep[Input] => Rep[Input]
  val ps = new scala.collection.mutable.LinkedHashMap[String,ToplevelParser]
  def toplevel_parser(s: String, p: Parser[Unit]): ToplevelParser =
    ps.getOrElseUpdate(s, toplevel("p_"+s.replaceAll("[^A-Za-z0-9]", ""),
    { in: Rep[Input] =>
      var out = in
      p(in).apply(
        (_, next) => out = next,
             next => out = unit(0).asInstanceOf[Rep[Input]]) // ignore next on failure
      out: Rep[Input]
    },
    { in: Rep[Input] => valid_input(in) },
    { in: Rep[Input] => out: Rep[Input] => unit(0) == out || valid_input(out) }))

  override def accept(s: String): Parser[Unit] = {
    val f = toplevel_parser(s, super.accept(s))
    Parser[Unit] { in =>
      val out = f(in)
      conditional(
        unit(0) != out,
        ParseResultCPS.Success(unit(()), out),
        ParseResultCPS.Failure(in))
    }
  }
}

trait TweakParser extends StagedParser { import Parser._
  override def repUnit[T: Typ](p: Parser[T]) = Parser[Unit] { input =>
    var in = input
    var c = unit(true)
    loop (valid_input(in), List[Any](in, c), 0) {
    while (c) {
      p(in).apply[Unit](
        (_, next) => { in = next },
        next => { c = false })
    }}
    ParseResultCPS.Success((), in)
  }
}

class ParserTests extends TestSuite {
  val under = "parse"

  test("0") {
    trait P0 extends StagedParser { import Parser._
      val p = toplevel("p",
        { in: Rep[Input] => phrase(digit2Int, in, -1) },
        { in: Rep[Input] => valid_input(in) },
        { in: Rep[Input] => result: Rep[Int] =>
          result == -1 || (0 <= result && result <= 9)
        })
    }
    check("0", (new P0 with Impl).code)
  }

  // overflow failures
  test("1") {
    trait P1 extends StagedParser { import Parser._
      val p = toplevel("p",
        { in: Rep[Input] =>
          phrase(rep(digit2Int, 0, { (a: Rep[Int], x: Rep[Int]) => a*10+x }), in, -1)
        },
        { in: Rep[Input] => valid_input(in) },
        { in: Rep[Input] => result: Rep[Int] => unit(true) })
    }
    check("1", (new P1 with Impl).code)
  }

  // overflow verifies
  test("2") {
    trait P2 extends StagedParser { import Parser._
      val p = toplevel("p",
        { in: Rep[Input] =>
          val m = Int.MaxValue / 10 - 10
          phrase(
            rep(digit2Int, 0,
              { (a: Rep[Int], x: Rep[Int]) =>
                if (a<0) a
                else if (a>m) -1
                else a*10+x
              },
              Some({ a: Rep[Int] => (a == -1) || (0 <= a) })),
            in, -1)
        },
        { in: Rep[Input] => valid_input(in) },
        { in: Rep[Input] => result: Rep[Int] =>
          (result == -1) || (0 <= result)
        })
    }
    check("2", (new P2 with Impl).code)
  }

  test("3a") {
    trait P3a extends HttpParser {
      override def http = headers
      val p = top
    }
    check("3a", (new P3a with Impl).code)
  }

  test("3") {
    trait P3 extends HttpParser with ToplevelAcceptParser with TweakParser {
      val p = top
    }
    check("3", (new P3 with Impl).code)
  }

  test("4") {
    trait P4 extends ChunkedHttpParser {
      override def http = chunkedBody
      val p = top
    }
    check("4", (new P4 with Impl).code)
  }

  test("5") {
    trait P5 extends ChunkedHttpParser with ToplevelAcceptParser with TweakParser {
      val p = top

      // Variations

      // still parse ignored nat
      //override def ignoredNat = nat ^^^ ()

      // no overflow check
      //override def overflowOrPos = None
      //override def numAcc(b: Int) = { (a: Rep[Int], x: Rep[Int]) => a*b+x }
    }
    check("5", (new P5 with Impl).code)
  }
}
