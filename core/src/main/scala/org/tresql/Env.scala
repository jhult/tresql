package org.tresql

import sys._
import CoreTypes.RowConverter
import parsing.{Exp, QueryParsers}

import scala.util.Try

/** Environment for expression building and execution */
private [tresql] class Env(_provider: EnvProvider, resources: Resources, val reusableExpr: Boolean)
  extends Resources with Metadata {

  def this(provider: EnvProvider, reusableExpr: Boolean) = this(provider, null, reusableExpr)
  def this(resources: Resources, reusableExpr: Boolean) = this(null: EnvProvider, resources, reusableExpr)
  def this(params: Map[String, Any], resources: Resources, reusableExpr: Boolean) = {
    this(null: EnvProvider, resources, reusableExpr)
    update(params)
  }

  //provided envs are used for statement closing. this list is filled only if provider is not set.
  //NOTE: list contains also this environment
  private var providedEnvs: List[Env] = Nil
  //is package private since is accessed from QueryBuilder
  private[tresql] val provider: Option[EnvProvider] = Option(_provider)

  private def rootEnv(e: Env): Env = e.provider.map(p => rootEnv(p.env)).getOrElse(e)
  private val root = rootEnv(this)
  root.providedEnvs = this :: root.providedEnvs

  private var vars: Option[scala.collection.mutable.Map[String, Any]] = None
  private var _exprs: Option[Map[Expr, Int]] = None
  private val ids = scala.collection.mutable.Map[String, Any]()
  private var _result: Result[_ <: RowLike] = _
  private var _statement: java.sql.PreparedStatement = _
  //stores row count returned by SelectResult and all subresults.
  //if resources.maxResultSize is greater than zero
  //Row count is accumulated only for top level Env i.e. provider is None
  private var _rowCount = 0
  //used in macro to convert result at certain query depth and child position to macro generated object
  //converter map is set from macro and are stored in level Env object i.e. provider is None
  private var _rowConverters: Option[Map[(Int /*query depth*/,
    Int /*col idx*/), RowConverter[_ <: RowLike]]] = None

  def apply(name: String): Any = get(name).map {
    case e: Expr => e()
    case x => x
  } getOrElse (throw new MissingBindVariableException(name))
  def apply(name: String, path: List[String]) = get(name, path)
    .getOrElse(throw new MissingBindVariableException((name :: path).mkString(".")))

  def get(name: String): Option[Any] =
    vars.flatMap(_.get(name)) orElse provider.flatMap(_.env.get(name))
  def get(name: String, path: List[String]): Option[Any] = {
    def tr(p: List[String], v: Option[Any]): Option[Any] = p match {
      case Nil => v
      case n :: rest => v flatMap {
        case m: Map[String, _] => tr(rest, m.get(n))
        case p: Product => tr(rest,
          Try(n.toInt).flatMap(v => Try(p.productElement(v - 1))).toOption)
        case null => Some(null)
        case x => None
      }
    }
    tr(path, get(name))
  }

  /* if not found into this variable map look into provider's if such exists */
  def contains(name: String): Boolean =
    vars.map(_.contains(name))
     .filter(_ == true)
     .getOrElse(provider.exists(_.env.contains(name)))
  def contains(name: String, path: List[String]): Boolean = {
    def tr(p: List[String], v: Any): Boolean = p match {
      case Nil => true
      case n :: rest => v match {
        case m: Map[String, _] => m.get(n).exists(tr(rest, _))
        case p: Product => Try(n.toInt)
          .flatMap(v => Try(p.productElement(v - 1)))
          .map(tr(rest, _))
          .getOrElse(false)
        case null => true
        case _ => false
      }
    }
    get(name).exists(tr(path, _))
  }

  /* finds closest env with vars map set (Some(vars)) and looks there if variable exists */
  def containsNearest(name: String): Boolean =
    vars.map(_.contains(name)).getOrElse(provider.exists(_.env.containsNearest(name)))

  private[tresql] def update(name: String, value: Any) {
    vars.map(_(name) = value) orElse provider.map(_.env(name) = value)
  }

  private[tresql] def update(vars: Map[String, Any]) {
    this.vars = if (vars == null) None else Some(scala.collection.mutable.Map(vars.toList: _*))
  }

  private [tresql] def updateExprs(exprs: Map[Expr, Int]) = _exprs = Option(exprs)

  def apply(rIdx: Int): Result[_ <: RowLike] = {
    var i = 0
    var e: Env = this
    while (i < rIdx && e != null) {
      e = e.provider.map(_.env).orNull
      i += 1
    }
    if (i == rIdx && e != null) e.result else error("Result not available at index: " + rIdx)
  }

  def apply(expr: Expr): Int = _exprs.map(_.getOrElse(expr,
      error(s"Expression not found in env: $this. Expr: $expr"))).getOrElse(
          error(s"Expression '$expr' not found. Hidden column expressions not set in environment: $this"))

  private[tresql] def statement = _statement
  private[tresql] def statement_=(st: java.sql.PreparedStatement) = _statement = st

  private[tresql] def result = _result
  private[tresql] def result_=(r: Result[_ <: RowLike]) = _result = r

  private[tresql] def closeStatement {
    root.providedEnvs foreach (e=> if (e.statement != null) e.statement.close)
  }

  private[tresql] def nextId(seqName: String): Any = {
    import CoreTypes._
    //TODO perhaps built expressions can be used to improve performance?
    implicit val implres = this
    val id = Query.unique[Any](implres.idExpr(seqName))
    ids(seqName) = id
    id
  }
  private[tresql] def currId(seqName: String): Any = (ids.get(seqName) orElse provider.map(_.env.currId(seqName))).get
  private[tresql] def currIdOption(seqName: String): Option[Any] =
    ids.get(seqName) orElse provider.flatMap(_.env.currIdOption(seqName))
  //update current id. This is called from QueryBuilder.IdExpr
  private[tresql] def currId(seqName: String, id: Any): Unit = ids(seqName) = id

  /* like currId, with the difference that this.ids is not searched */
  private[tresql] def ref(seqName: String) = provider.map(_.env.currId(seqName))
  private[tresql] def refOption(seqName: String) = provider.flatMap(_.env.currIdOption(seqName))

  private[tresql] def rowCount: Int = provider.map(_.env.rowCount).getOrElse(_rowCount)
  private[tresql] def rowCount_=(rc: Int) {
    provider.map(_.env.rowCount = rc).getOrElse (this._rowCount = rc)
  }

  private[tresql] def rowConverter(depth: Int, child: Int): Option[RowConverter[_ <: RowLike]] =
    rowConverters.flatMap(_.get((depth, child)))
  private[tresql] def rowConverters: Option[Map[(Int /*query depth*/,
    Int /*child idx*/), RowConverter[_ <: RowLike]]] =
    provider.flatMap(_.env.rowConverters) orElse _rowConverters
  private[tresql] def rowConverters_=(rc: Map[(Int /*query depth*/,
    Int /*child idx*/), RowConverter[_ <: RowLike]]) {
    provider.map(_.env.rowConverters = rc).getOrElse (this._rowConverters = Option(rc))
  }

  //resources methods
  override def conn: java.sql.Connection = provider.map(_.env.conn).getOrElse(resources.conn)
  override def metadata = provider.map(_.env.metadata).getOrElse(resources.metadata)
  override def dialect: CoreTypes.Dialect = provider.map(_.env.dialect).getOrElse(resources.dialect)
  override def idExpr = provider.map(_.env.idExpr).getOrElse(resources.idExpr)
  override def queryTimeout: Int = provider.map(_.env.queryTimeout).getOrElse(resources.queryTimeout)
  override def fetchSize: Int = provider.map(_.env.fetchSize).getOrElse(resources.fetchSize)
  override def maxResultSize: Int = provider.map(_.env.maxResultSize).getOrElse(resources.maxResultSize)
  override def recursiveStackDepth: Int = provider.map(_.env.recursiveStackDepth).getOrElse(resources.recursiveStackDepth)
  override def cache: Cache = provider.map(_.env.cache).getOrElse(resources.cache)
  override def logger: TresqlLogger = provider.map(_.env.logger).getOrElse(resources.logger)
  override def bindVarLogFilter: BindVarLogFilter = provider.map(_.env.bindVarLogFilter).getOrElse(resources.bindVarLogFilter)
  override def isMacroDefined(name: String): Boolean =
    provider.map(_.env.isMacroDefined(name)).getOrElse(resources.isMacroDefined(name))
  override def isBuilderMacroDefined(name: String): Boolean =
    provider.map(_.env.isBuilderMacroDefined(name)).getOrElse(resources.isBuilderMacroDefined(name))
  override def invokeMacro[T](name: String, parser_or_builder: AnyRef, args: List[T]): T =
    provider.map(_.env.invokeMacro(name, parser_or_builder, args))
      .getOrElse(resources.invokeMacro(name, parser_or_builder, args))

  //meta data methods
  override def table(name: String) = metadata.table(name)
  override def tableOption(name:String) = metadata.tableOption(name)
  override def procedure(name: String) = metadata.procedure(name)
  override def procedureOption(name:String) = metadata.procedureOption(name)

  //debugging methods
  def variables = "\nBind variables:" +
    vars.map(_.mkString("\n ", "\n ", "\n")).getOrElse("<none>")
  def allVariables = "\nBind variables:\n" +
    valsAsString("  ", this, e => e.vars.getOrElse(Map.empty))
  def allIds = "\nIds:\n" + valsAsString("  ", this, _.ids)
  private def valsAsString(offset: String, env: Env, vals: Env => scala.collection.Map[String, Any]): String =
    vals(env).mkString(s"\n$offset<vals>\n$offset", "\n" + offset, s"\n${offset}<vals end>\n") +
        env.provider.map(p => valsAsString(offset * 2, p.env, vals)).getOrElse("")

  override def toString: String = super.toString +
    provider.map(p=> s":$p#${p.env.toString}").getOrElse("<no provider>")

}

/** Implementation of [[Resources]] with thread local instance based on template */
trait ThreadLocalResources extends Resources {

  protected def resourcesTemplate: ResourcesTemplate = new ResourcesTemplate(new Resources {}) // empty resources

  private val _threadResources = new ThreadLocal[Resources] {
    override def initialValue(): Resources = resourcesTemplate.copyResources
  }

  private def threadResources: Resources = _threadResources.get
  private def threadResources_=(res: Resources): Unit = _threadResources.set(res)

  case class ResourcesTemplate(override val conn: java.sql.Connection,
                               override val metadata: Metadata,
                               override val dialect: CoreTypes.Dialect,
                               override val idExpr: String => String,
                               override val queryTimeout: Int,
                               override val fetchSize: Int,
                               override val maxResultSize: Int,
                               override val recursiveStackDepth: Int,
                               override val params: Map[String, Any],
                               macros: Any = null) extends Resources {
    private [ThreadLocalResources] def this(res: Resources) = this(
      res.conn, res.metadata, res.dialect, res.idExpr, res.queryTimeout,
      res.fetchSize, res.maxResultSize, res.recursiveStackDepth, res.params)
    override protected[tresql] def copyResources: Resources_ =
      super.copyResources.withMacros(macros).asInstanceOf[Resources_]
  }

  def apply(params: Map[String, Any], reusableExpr: Boolean) = new Env(params, this, reusableExpr)

  override def conn = threadResources.conn
  override def metadata = threadResources.metadata
  override def dialect = threadResources.dialect
  override def idExpr = threadResources.idExpr
  override def queryTimeout = threadResources.queryTimeout
  override def fetchSize = threadResources.fetchSize
  override def maxResultSize = threadResources.maxResultSize
  override def recursiveStackDepth: Int = threadResources.recursiveStackDepth
  override def isMacroDefined(macroName: String) = threadResources.isMacroDefined(macroName)
  override def isBuilderMacroDefined(macroName: String) = threadResources.isBuilderMacroDefined(macroName)
  override def invokeMacro[T](name: String, parser_or_builder: AnyRef, args: List[T]): T = {
    threadResources.invokeMacro(name, parser_or_builder, args)
  }

  /** Cache is global not thread local. To be overriden in subclasses. This implementation returns {{{super.cache}}} */
  override def cache: Cache = super.cache
  /** Logger is global not thread local. To be overriden in subclasses. This implementation returns {{{super.logger}}} */
  override def logger: TresqlLogger = super.logger
  /** Filter is global not thread local. To be overriden in subclasses. This implementation returns {{{super.bindVarLogFilter}}} */
  override def bindVarLogFilter: BindVarLogFilter = super.bindVarLogFilter

  private def setProp(f: Resources => Resources): Unit = threadResources = f(threadResources)

  def conn_=(conn: java.sql.Connection) = setProp(_.withConn(conn))
  def metadata_=(metadata: Metadata) = setProp(_.withMetadata(metadata))
  def dialect_=(dialect: CoreTypes.Dialect) = setProp(_.withDialect(dialect))
  def idExpr_=(idExpr: String => String) = setProp(_.withIdExpr(idExpr))
  def recursiveStackDepth_=(depth: Int) = setProp(_.withRecursiveStackDepth(depth))
  def queryTimeout_=(timeout: Int) =  setProp(_.withQueryTimeout(timeout))
  def fetchSize_=(fetchSize: Int) =  setProp(_.withFetchSize(fetchSize))
  def maxResultSize_=(size: Int) = setProp(_.withMaxResultSize(size))
  def setMacros(macros: Any) = setProp(_.withMacros(macros))
}

/** Resources and configuration for query execution like database connection, metadata, database dialect etc. */
trait Resources extends MacroResources with CacheResources with Logging {

  private [tresql] case class Resources_(
    override val conn: java.sql.Connection,
    override val metadata: Metadata,
    override val dialect: CoreTypes.Dialect,
    override val idExpr: String => String,
    override val queryTimeout: Int,
    override val fetchSize: Int,
    override val maxResultSize: Int,
    override val recursiveStackDepth: Int,
    override val cache: Cache,
    override val logger: TresqlLogger,
    override val bindVarLogFilter: BindVarLogFilter,
    override val params: Map[String, Any],
    macros: MacroResources) extends Resources {
    override def isMacroDefined(name: String) = macros.isMacroDefined(name)
    override def isBuilderMacroDefined(name: String) = macros.isBuilderMacroDefined(name)
    override def invokeMacro[T](name: String, parser_or_builder: AnyRef, args: List[T]): T =
      macros.invokeMacro(name, parser_or_builder, args)
    override def toString = s"Resources_(conn = $conn, " +
      s"metadata = $metadata, dialect = $dialect, idExpr = $idExpr, " +
      s"queryTimeout = $queryTimeout, fetchSize = $fetchSize, " +
      s"maxResultSize = $maxResultSize, recursiveStackDepth = $recursiveStackDepth, cache = $cache" +
      s"logger =$logger, bindVarLogFilter = $bindVarLogFilter" +
      s" params = $params)"
  }

  def conn: java.sql.Connection = null
  def metadata: Metadata = null
  def dialect: CoreTypes.Dialect = null
  def idExpr: String => String = s => "nextval('" + s + "')"
  def queryTimeout = 0
  def fetchSize = 0
  def maxResultSize = 0
  def recursiveStackDepth: Int = 50
  def params: Map[String, Any] = Map()

  //resource construction convenience methods
  def withConn(conn: java.sql.Connection): Resources = copyResources.copy(conn = conn)
  def withMetadata(metadata: Metadata): Resources = copyResources.copy(metadata = metadata)
  def withDialect(dialect: CoreTypes.Dialect): Resources =
    copyResources.copy(dialect = liftDialect(dialect))
  def withIdExpr(idExpr: String => String): Resources = copyResources.copy(idExpr = idExpr)
  def withQueryTimeout(queryTimeout: Int): Resources = copyResources.copy(queryTimeout = queryTimeout)
  def withFetchSize(fetchSize: Int): Resources = copyResources.copy(fetchSize = fetchSize)
  def withMaxResultSize(maxResultSize: Int): Resources = copyResources.copy(maxResultSize = maxResultSize)
  def withRecursiveStackDepth(recStackDepth: Int): Resources = copyResources.copy(recursiveStackDepth = recStackDepth)
  def withCache(cache: Cache): Resources = copyResources.copy(cache = cache)
  def withLogger(logger: TresqlLogger): Resources = copyResources.copy(logger = logger)
  def withBindVarLogFilter(filter: BindVarLogFilter): Resources = copyResources.copy(bindVarLogFilter = filter)
  def withParams(params: Map[String, Any]): Resources = copyResources.copy(params = params)
  def withMacros(macros: Any): Resources = copyResources.copy(macros = new MacroResourcesImpl(macros))

  protected def copyResources: Resources_ = this match {
    case r: Resources_ => r
    case _ => Resources_(conn, metadata, liftDialect(dialect), idExpr, queryTimeout,
      fetchSize, maxResultSize, recursiveStackDepth, cache, logger, bindVarLogFilter,
      params, this)
  }

  protected def defaultDialect: CoreTypes.Dialect = { case e => e.defaultSQL }

  protected def liftDialect(dialect: CoreTypes.Dialect) =
    if (dialect == null) null else dialect orElse defaultDialect
}

trait MacroResources {
  def isMacroDefined(name: String): Boolean = false
  def isBuilderMacroDefined(name: String): Boolean = false
  def invokeMacro[T](name: String, parser_or_builder: AnyRef, args: List[T]): T =
    sys.error(s"Macro function not found: $name")
}

class MacroResourcesImpl(macros: Any) extends MacroResources {
  private val (methods, invocationTarget) = macroMethods(macros)

  private def macroMethods(obj: Any): (Map[(String, Boolean), java.lang.reflect.Method], Any) = obj match {
    case null => (Map(), null)
    case Some(o) => macroMethods(o)
    case None => macroMethods(null)
    case x => {
      def isMacro(m: java.lang.reflect.Method) =
        m.getParameterTypes.nonEmpty && (isParserMacro(m) || isBuilderMacro(m))
      def isParserMacro(m: java.lang.reflect.Method) =
        classOf[QueryParsers].isAssignableFrom(m.getParameterTypes()(0)) &&
          classOf[Exp].isAssignableFrom(m.getReturnType)
      def isBuilderMacro(m: java.lang.reflect.Method) =
        classOf[QueryBuilder].isAssignableFrom(m.getParameterTypes()(0)) &&
          classOf[Expr].isAssignableFrom(m.getReturnType)
      val mm = x.getClass.getMethods.collect {
        case m if isMacro(m) => (m.getName -> isParserMacro(m), m)
      }.toMap
      if (mm.isEmpty) sys.error(s"No macro methods found in object $obj. " +
        s"If you do not want to use macros pass null as a parameter")
      (mm, x)
    }
  }

  override def isMacroDefined(name: String) = methods.contains((name, true))
  override def isBuilderMacroDefined(name: String) = methods.contains((name, false))
  override def invokeMacro[T](name: String, parser_or_builder: AnyRef, args: List[T]): T = {
    val m = (methods.get(name, true) orElse methods.get(name, false)).get
    val p = m.getParameterTypes
    if (p.length > 1 && p(1).isAssignableFrom(classOf[Seq[_]])) {
      //parameter is list of expressions
      m.invoke(invocationTarget, parser_or_builder, args).asInstanceOf[T]
    } else {
      val _args = (parser_or_builder :: args).asInstanceOf[Seq[Object]] //must cast for older scala verions
      m.invoke(invocationTarget, _args: _*).asInstanceOf[T]
    }
  }
}

trait CacheResources {
  /** Parsed statement {{{Cache}}} */
  def cache: Cache = null
}

trait Logging {
  type TresqlLogger = (=> String, => Map[String, Any], LogTopic) => Unit
  type BindVarLogFilter = PartialFunction[Expr, String]

  def logger: TresqlLogger = null
  def bindVarLogFilter: BindVarLogFilter = {
    case v: QueryBuilder#VarExpr if v.name == "password" => v.fullName + " = ***"
  }

  def log(msg: => String, params: => Map[String, Any] = Map(), topic: LogTopic = LogTopic.info): Unit =
    if (logger != null) logger(msg, params, topic)
}

private [tresql] trait EnvProvider {
  private[tresql] def env: Env
}

class MissingBindVariableException(val name: String)
  extends RuntimeException(s"Missing bind variable: $name")

trait LogTopic
object LogTopic {
  case object tresql extends LogTopic
  case object sql extends LogTopic
  case object params extends LogTopic
  case object sql_with_params extends LogTopic
  case object info extends LogTopic
}
