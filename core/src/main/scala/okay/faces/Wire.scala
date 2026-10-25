package okay.faces

import okay.effects.json.Json
import okay.effects.shape.{Schema, Shape}

/**
 * WHAT CROSSES A WIRE between a dialogue and a face elsewhere: the view as JSON, and an action as an AFFORDANCE — its
 * name, label and doc with its parameters' erased `Schema` instead of the `Shape`. The REST face sends these; a client
 * that knows no application (the remote Laminar face) draws the view, draws a form from the schema, and sends back a
 * `Chosen`. `Affordance.action` is the client's `Action`: structural over the schema, so a choice is checked where it
 * is made before it travels.
 */
final case class Affordance(name: String, label: String, doc: Option[String], params: Schema,
  more: Set[String] = Set.empty, long: Set[String] = Set.empty) {
  def json: Json = Json.Obj(Vector("name" -> Json.Str(name), "label" -> Json.Str(label)) ++ doc.map(d =>
    "doc" -> Json.Str(d)) ++ Option.when(more.nonEmpty)("more" -> Json.Arr(more.toVector.sorted.map(Json.Str(_)))) ++
    Option.when(long.nonEmpty)("long" -> Json.Arr(long.toVector.sorted.map(Json.Str(_)))) :+
    ("params" -> Schema.shape.encode(params)))
  /** the action a client offers: the arguments checked against the schema, kept as JSON */
  def action: Action[Json] = Action.of[Json, Json](name, label, Schema.structural(params), doc, more, long)(j => j)
}
object Affordance {
  def decode(j: Json): Either[String, Affordance] = j match {
    case o: Json.Obj => for {
        name <- str(o, "name"); label <- str(o, "label")
        params <- o.get("params").toRight("no params").flatMap(p =>
          Schema.shape.decode(p).left.map(okay.effects.shape.Problem.render))
      } yield Affordance(name, label, o.get("doc").collect { case Json.Str(d) => d }, params,
        names(o, "more"), names(o, "long"))
    case other => Left(s"an affordance is an object, not ${Json.kind(other)}")
  }
  private def names(o: Json.Obj, k: String): Set[String] =
    o.get(k).collect { case Json.Arr(ns) => ns.collect { case Json.Str(n) => n }.toSet }.getOrElse(Set.empty)
  private def str(o: Json.Obj, k: String): Either[String, String] =
    o.get(k) match { case Some(Json.Str(s)) => Right(s); case _ => Left(s"no $k") }
}

/** a `View` as JSON, `{"type": ..., ...}`, and back */
object ViewJson {
  def encode(v: View): Json = v match {
    case View.Empty => Json.obj("type" -> Json.Str("empty"))
    case View.Text(s) => Json.obj("type" -> Json.Str("text"), "text" -> Json.Str(s))
    case View.Heading(level, s) =>
      Json.obj("type" -> Json.Str("heading"), "level" -> Json.num(level), "text" -> Json.Str(s))
    case View.Notice(level, s) =>
      Json.obj("type" -> Json.Str("notice"), "level" -> Json.Str(level.toString.toLowerCase), "text" -> Json.Str(s))
    case View.Fields(rows) => Json.obj("type" -> Json.Str("fields"),
        "rows" -> Json.Arr(rows.map { case (k, x) => Json.obj("label" -> Json.Str(k), "value" -> encode(x)) }))
    case View.Table(columns, rows) => Json.obj("type" -> Json.Str("table"),
        "columns" -> Json.Arr(columns.map(Json.Str(_))), "rows" -> Json.Arr(rows.map(r => Json.Arr(r.map(encode)))))
    case View.Items(items) => Json.obj("type" -> Json.Str("items"), "items" -> Json.Arr(items.map(encode)))
    case View.Group(title, children) => Json.Obj(Vector("type" -> Json.Str("group")) ++ title.map(t =>
        "title" -> Json.Str(t)) :+ ("children" -> Json.Arr(children.map(encode))))
    case View.Ref(key, label) => Json.obj("type" -> Json.Str("ref"), "key" -> Json.Str(key), "label" -> Json.Str(label))
    case View.Link(label, url) =>
      Json.obj("type" -> Json.Str("link"), "label" -> Json.Str(label), "url" -> Json.Str(url))
    case View.Datum(json, schema) => Json.Obj(Vector("type" -> Json.Str("datum"), "value" -> json) ++ schema.map(s =>
        "schema" -> Schema.shape.encode(s)))
    case View.Masked(hint) => Json.obj("type" -> Json.Str("masked"), "hint" -> Json.Str(hint))
    case View.Marked(mark, v) => Json.obj("type" -> Json.Str("marked"), "mark" -> Json.Str(mark), "view" -> encode(v))
  }

  def decode(j: Json): Either[String, View] = j match {
    case o: Json.Obj => o.get("type") match {
        case Some(Json.Str("empty")) => Right(View.Empty)
        case Some(Json.Str("text")) => str(o, "text").map(View.Text(_))
        case Some(Json.Str("heading")) => for { l <- int(o, "level"); s <- str(o, "text") } yield View.Heading(l, s)
        case Some(Json.Str("notice")) => for {
            l <- str(o, "level").flatMap {
              case "info" => Right(View.Notice.Info); case "warning" => Right(View.Notice.Warning);
              case "problem" => Right(View.Notice.Problem); case x => Left(s"no notice level $x")
            }
            s <- str(o, "text")
          } yield View.Notice(l, s)
        case Some(Json.Str("fields")) => arr(o, "rows").flatMap(rows =>
            all(rows.map {
              case r: Json.Obj =>
                for { k <- str(r, "label"); v <- r.get("value").toRight("no value").flatMap(decode) } yield (k, v)
              case x => Left(s"a row is an object, not ${Json.kind(x)}")
            })).map(View.Fields(_))
        case Some(Json.Str("table")) => for {
            cs <- arr(o, "columns").flatMap(cs =>
              all(cs.map { case Json.Str(c) => Right(c); case x => Left(s"a column is text, not ${Json.kind(x)}") }))
            rs <- arr(o, "rows").flatMap(rs =>
              all(rs.map {
                case Json.Arr(cells) => all(cells.map(decode));
                case x => Left(s"a row is an array, not ${Json.kind(x)}")
              }))
          } yield View.Table(cs, rs)
        case Some(Json.Str("items")) => arr(o, "items").flatMap(xs => all(xs.map(decode))).map(View.Items(_))
        case Some(Json.Str("group")) => arr(o, "children").flatMap(xs => all(xs.map(decode))).map(
            View.Group(o.get("title").collect { case Json.Str(t) => t }, _))
        case Some(Json.Str("ref")) => for { k <- str(o, "key"); l <- str(o, "label") } yield View.Ref(k, l)
        case Some(Json.Str("link")) => for { l <- str(o, "label"); u <- str(o, "url") } yield View.Link(l, u)
        case Some(Json.Str("datum")) => o.get("value").toRight("no value").flatMap(v =>
            o.get("schema") match {
              case None => Right(View.Datum(v, None))
              case Some(s) =>
                Schema.shape.decode(s).left.map(okay.effects.shape.Problem.render).map(sc => View.Datum(v, Some(sc)))
            })
        case Some(Json.Str("masked")) => str(o, "hint").map(View.Masked(_))
        case Some(Json.Str("marked")) =>
          for { m <- str(o, "mark"); v <- o.get("view").toRight("no view").flatMap(decode) } yield View.Marked(m, v)
        case Some(Json.Str(t)) => Left(s"no view type $t")
        case _ => Left("a view has a type")
      }
    case other => Left(s"a view is an object, not ${Json.kind(other)}")
  }
  private def names(o: Json.Obj, k: String): Set[String] =
    o.get(k).collect { case Json.Arr(ns) => ns.collect { case Json.Str(n) => n }.toSet }.getOrElse(Set.empty)
  private def str(o: Json.Obj, k: String): Either[String, String] =
    o.get(k) match { case Some(Json.Str(s)) => Right(s); case _ => Left(s"no $k") }
  private def int(o: Json.Obj, k: String): Either[String, Int] =
    o.get(k) match { case Some(Json.Num(n)) => Right(n.toInt); case _ => Left(s"no $k") }
  private def arr(o: Json.Obj, k: String): Either[String, Vector[Json]] =
    o.get(k) match { case Some(Json.Arr(xs)) => Right(xs); case _ => Left(s"no $k") }
  private def all[A](xs: Vector[Either[String, A]]): Either[String, Vector[A]] =
    xs.foldLeft[Either[String, Vector[A]]](Right(Vector.empty))((acc, x) => acc.flatMap(as => x.map(as :+ _)))
}

/** A FORM'S FIELDS AND A QUERY'S, by dotted name, to JSON along a schema and back: what a page posts, what a GET
 * carries.
 * Blank is absent; a checkbox absent is false; a list is the key repeated, or one value with a line per item; a sum
 * is `path.type` and the case's fields under `path.case` */
object FormJson {
  private def name(path: List[String]) = path.mkString(".")
  def collect(schema: Schema, form: Map[String, List[String]]): Json = {
    def values(path: List[String]): List[String] = form.get(name(path)).getOrElse(Nil).filter(_.nonEmpty)
    def value(path: List[String]): Option[String] = values(path).headOption
    def go(path: List[String], s: Schema): Json = s match {
      case Schema.Record(fs) => Json.Obj(
          fs.flatMap(f => go(path :+ f.name, f.schema) match { case Json.Null => None; case v => Some(f.name -> v) }))
      case Schema.Described(of, _, _) => go(path, of)
      case Schema.Optional(of) => go(path, of)
      case Schema.Choice(_) => value(path).map(Json.Str(_)).getOrElse(Json.Null)
      case Schema.OneOf(cases) =>
        form.get(name(path :+ "type")).flatMap(_.headOption).flatMap(n => cases.find(_._1 == n)) match {
          case Some((n, cs)) => go(path :+ n, cs) match {
              case o: Json.Obj if Schema.isRecord(cs) => ("type" -> Json.Str(n)) +: o
              case v => Json.Obj.of("type" -> Json.Str(n), "value" -> v)
            }
          case None => Json.Null
        }
      case Schema.Many(of, _, _) if !FormJson.leafish(of) =>
        // items by index, `path.0.field`, `path.1.field`: a slot left blank is no item
        val prefix = if (path.isEmpty) "" else name(path) + "."
        val indices = form.keys.toList.collect {
          case k
              if k.startsWith(prefix) && k.drop(prefix.length).takeWhile(_ != '.').forall(_.isDigit) && k.drop(
                prefix.length).takeWhile(_ != '.').nonEmpty => k.drop(prefix.length).takeWhile(_ != '.').toInt
        }.distinct.sorted
        val items = indices.map(i => go(path :+ i.toString, of)).filter {
          case Json.Obj(fs) => fs.nonEmpty; case Json.Null => false; case _ => true
        }
        if (items.isEmpty && indices.isEmpty) Json.Null else Json.Arr(items.toVector)
      case Schema.Many(of, _, _) => values(path) match {
          case Nil => Json.Null
          case one :: Nil => Json.Arr(one.split("\n").toVector.map(_.trim).filter(_.nonEmpty).map(typed(of, _)))
          case many => Json.Arr(many.toVector.map(typed(of, _)))
        }
      case Schema.Bool =>
        Json.Bool(form.get(name(path)).exists(_.exists(v => Set("true", "on", "yes").contains(v.toLowerCase))))
      case leaf => value(path).map(typed(leaf, _)).getOrElse(Json.Null)
    }
    go(Nil, schema)
  }
  /** a leaf, or an optional or described one: what one line of text says; a record, a sum, a list is not */
  def leafish(s: Schema): Boolean = s match {
    case Schema.Record(_) | Schema.OneOf(_) | Schema.Many(_, _, _) | Schema.Ref(_) => false
    case Schema.Described(of, _, _) => leafish(of)
    case Schema.Optional(of) => leafish(of)
    case _ => true
  }
  def typed(schema: Schema, s: String): Json = schema match {
    case Schema.Integer(_) | Schema.Number(_) =>
      scala.util.Try(BigDecimal(s.trim)).toOption.fold[Json](Json.Str(s))(Json.Num(_))
    case Schema.Bool => Json.Bool(Set("true", "on", "yes").contains(s.trim.toLowerCase))
    case Schema.Described(of, _, _) => typed(of, s)
    case _ => Json.Str(s)
  }
  /** the other way, along the schema: a GET's query from arguments */
  def print(schema: Schema, json: Json): Map[String, List[String]] = {
    def leaf(v: Json): String = v match { case Json.Str(s) => s; case other => Json.print(other) }
    def go(path: List[String], s: Schema, v: Json): List[(String, String)] = (s, v) match {
      case (_, Json.Null) => Nil
      case (Schema.Record(fs), o: Json.Obj) =>
        fs.toList.flatMap(f => o.get(f.name).toList.flatMap(go(path :+ f.name, f.schema, _)))
      case (Schema.Described(of, _, _), _) => go(path, of, v)
      case (Schema.Optional(of), _) => go(path, of, v)
      case (Schema.OneOf(cases), o: Json.Obj) => o.get("type") match {
          case Some(Json.Str(n)) => cases.find(_._1 == n).toList.flatMap { case (_, cs) =>
              (name(path :+ "type") -> n) :: go(path :+ n, cs,
                if (Schema.isRecord(cs)) o.without("type") else o.get("value").getOrElse(Json.Null))
            }
          case _ => Nil
        }
      case (Schema.Many(of, _, _), Json.Arr(xs)) if !FormJson.leafish(of) =>
        xs.toList.zipWithIndex.flatMap { case (x, i) => go(path :+ i.toString, of, x) }
      case (Schema.Many(of, _, _), Json.Arr(xs)) => xs.toList.map(x => name(path) -> leaf(x))
      case (_, other) => List(name(path) -> leaf(other))
    }
    go(Nil, schema, json).groupBy(_._1).map { case (k, kvs) => k -> kvs.map(_._2) }
  }
}
