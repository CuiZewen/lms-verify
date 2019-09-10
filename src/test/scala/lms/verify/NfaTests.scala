package lms.verify

trait NfaLib {
  type St = Int
  type StSet = Set[St]
  def empty_ss = Set.empty[St]
  type CharMap[T] = Map[Char,T]
  type Transitions = CharMap[StSet]
  def empty_t = Map.empty[Char,StSet]
  def set(xs: St*) = Set(xs: _*)
  case class Nfa(start: St, finals: StSet, next: St => Transitions)

  def find_states(sym: Char, nfa: Nfa, m: St): StSet =
    nfa.next(m).getOrElse(sym, Set.empty[St])

  def flat_map(f: St => StSet, ss: StSet): StSet =
    ss.foldRight(Set.empty[St]){case (s: St, t: StSet) => f(s).union(t)}
  def nextss(curs: StSet, sym: Char, nfa: Nfa): StSet = flat_map(find_states(sym, nfa, _), curs)

  def accept(nfa: Nfa, inp: Seq[Char]): Boolean = {
    def step(cur: StSet, cs: Seq[Char]): Boolean =
      if (cs.isEmpty) !nfa.finals.intersect(cur).isEmpty
      else step(nextss(cur, cs.head, nfa), cs.tail)
    step(Set(nfa.start), inp)
  }
}

trait LetrecLib {
  def letrec[A,B,C](rhs: (A => B) => A => Int => B, body: (A => B) => C): C = {
    var seen: Map[A,Int] = Map.empty
    var table: Map[A,B] = Map.empty
    def resolve(index: A): B =
      (table.get(index), seen.get(index)) match {
        case (Some(r), _) => r
        case (None, None) =>
          val id = seen.size
          seen += (index -> id)
          val r = rhs(resolve)(index)(id)
          table += (index -> r)
          r
        case (None, Some(id)) => id.asInstanceOf[B] // hack
      }
    body(resolve)
  }
}

trait DfaLib extends NfaLib with LetrecLib {
  type CharSet = Set[Char]
  type StSetMap[T] = Map[StSet,T]
  def empty_ssm = Map.empty:StSetMap[CharSet]

  def next_states(nfa: Nfa, s: St): StSetMap[CharSet] =
    nfa.next(s).foldLeft(empty_ssm){(ssm, kv) =>
      val c = kv._1
      val v = kv._2
        ssm.get(v) match {
          case None => ssm + (v -> Set(c))
          case Some(s) => ssm + (v -> (s + c))
        }
    }

  def mergeMaps(maps: StSetMap[CharSet]*): StSetMap[CharSet] =
    maps.foldLeft(empty_ssm){
      (r: StSetMap[CharSet], m: StSetMap[CharSet]) =>
      m.foldLeft(r){
        (dict: StSetMap[CharSet], (kv: (StSet,CharSet))) =>
        val k = kv._1; val v = kv._2
        dict + (k -> (v.union(dict.getOrElse(k, Set.empty[Char]))))
      }}

  def nexts(nfa: Nfa, cur: StSet): StSetMap[CharSet] =
    cur.foldLeft(empty_ssm){
      (ssm: StSetMap[CharSet], st: St) =>
      mergeMaps(ssm, next_states(nfa, st))
    }

  case class Dfa(finals: Vector[Boolean], transitions: Vector[Vector[CharSet]]) {
    override def toString = "Dfa("+finals+", Vector(\n"+
    transitions.map{ts => ts.map{cs => cs.map("'"+_+"'")}}.mkString(",\n")+"))"
  }
  def nfa2dfa(nfa: Nfa): Dfa = {
    import scala.collection.mutable.ArrayBuffer

    def grow[A](x: ArrayBuffer[A], size: Int, v: A) = {
      (for (i <- 0 until (size-x.size)) yield
        x += v
      )
      x
    }

    val finals: ArrayBuffer[Boolean] = new ArrayBuffer[Boolean]()
    val transitions: ArrayBuffer[ArrayBuffer[CharSet]] = new ArrayBuffer[ArrayBuffer[CharSet]]()
    def rhs(step: (StSet => Int))(cur: StSet)(id: Int) = {
      grow(finals, id+1, false)
      grow(transitions, id+1, null)
      finals(id) = !(cur intersect nfa.finals).isEmpty
      transitions(id) = new ArrayBuffer[CharSet](id+1)
      nexts(nfa, cur).foldLeft{()}{(r: Unit, kv: (StSet, CharSet)) =>
        val ss = kv._1
        val cs = kv._2
        val next_id = step(ss)
        grow(transitions(id), next_id+1, Set[Char]())
        transitions(id)(next_id) = cs
      }
      id
    }
    letrec(rhs,
      {(resolve: (StSet => Int)) =>
        resolve(set(nfa.start))})
    val finalsVec = finals.toVector
    val transitionsVec = transitions.toVector.map{ts => grow(ts, finalsVec.size, Set[Char]()).toVector}
    Dfa(finalsVec, transitionsVec)
  }
}

trait NfaStagedLib extends NfaLib with DfaLib with LetrecLib with Dsl with Reader {
  def infix_contains(cs: CharSet, c: Rep[Char]) =
    cs.foldLeft(unit(false))(_ || _==c)

  def splitc(nfa: Nfa, cur: StSet, c: Rep[Char], k: StSet => Rep[Boolean]): Rep[Boolean] = {
    nexts(nfa, cur).foldLeft{unit(false)}{(r: Rep[Boolean], kv: (StSet, CharSet)) =>
      val ss = kv._1
      val cs = kv._2
      if (cs.contains(c)) k(ss) else r
    }
  }
  def staged_accept(nfa: Nfa) = {
    def rhs(step: (StSet => Rep[Array[Char]] => Rep[Boolean]))(ss: StSet)(id: Int) = toplevel("nfa_"+id, {(cs: Rep[Array[Char]]) =>
      if (cs.atEnd) unit(!(ss intersect nfa.finals).isEmpty)
      else splitc(nfa, ss, cs.first, {(ss: StSet) => step(ss)(cs.rest) })})
    letrec(rhs,
      {(resolve: (StSet => Rep[Array[Char]] => Rep[Boolean])) =>
        resolve(set(nfa.start))})
  }
}

trait DfaExamples extends DfaLib {
  val dfa1 = Dfa(Vector(false, false, true), Vector(
    Vector(Set(), Set('A'), Set()),
    Vector(Set(), Set('A'), Set('B')),
    Vector(Set(), Set(), Set())))
}

trait NfaExamples extends NfaLib {
  val nfa1 = Nfa(1, set(3), {s => s match {
    case 1 => Map(('A' -> set(2)))
    case 2 => Map(('A' -> set(2)), ('B' -> set(3)))
    case 3 => empty_t
  }})

  // equivalent
  val nfa2 = Nfa(1, set(3), {s => s match {
    case 1 => Map(('A' -> set(1,2)))
    case 2 => Map(('A' -> set(1,2)), ('B' -> set(3)))
    case 3 => empty_t
  }})
}

class DfaTests extends TestSuite {
  val under = "nfa2dfa_"

  test("1") {
    trait Dfa1 extends DfaLib with NfaExamples with DfaExamples {
      val res = nfa2dfa(nfa1)
      println(res)
    }
    lazy val ex = new Dfa1 {}
    checkOut("aapb", ex, "scala")
    assert(ex.res == ex.dfa1)
  }
}

class NfaTests extends TestSuite {
  val under = "nfa_"

  test("1") {
    trait Nfa1 extends NfaStagedLib with NfaExamples {
      val machine = staged_accept(nfa1)
    }
    check("aapb", (new Nfa1 with Impl).code)
  }

  test("2") {
    trait Nfa2 extends NfaStagedLib with NfaExamples {
      val machine = staged_accept(nfa2)
    }
    check("aapb", (new Nfa2 with Impl).code)
  }
}