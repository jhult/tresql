package org.tresql

import scala.annotation.tailrec

import parsing.{QueryParsers, Exp}

package object macro_ {
  implicit class TresqlMacroInterpolator(val sc: StringContext) extends AnyVal {
    def macro_(args: Exp*)(implicit p: QueryParsers): Exp = {
      p.parseExp(sc.standardInterpolator(StringContext.processEscapes, args.map(_.tresql)))
    }
  }
}

class Macros {
  def sql(b: QueryBuilder, const: QueryBuilder#ConstExpr) = b.SQLExpr(String valueOf const.value, Nil)

  private def containsVar(b: QueryBuilder, v: QueryBuilder#VarExpr) =
    if (v.members != null && v.members.nonEmpty) b.env.contains(v.name, v.members)
    else b.env contains v.name

  def if_defined(b: QueryBuilder, v: Expr, e: Expr) = v match {
    case ve: QueryBuilder#VarExpr => if (containsVar(b, ve)) e else null
    case null => null
    case _ => e
  }

  def if_defined_or_else(b: QueryBuilder, v: Expr, e1: Expr, e2: Expr) =
    Option(if_defined(b, v, e1)).getOrElse(e2)

  def if_missing(b: QueryBuilder, v: Expr, e: Expr) = v match {
    case ve: QueryBuilder#VarExpr => if (containsVar(b, ve)) null else e
    case null => e
    case _ => null
  }

  def if_all_defined(b: QueryBuilder, e: Expr*) = {
    if (e.size < 2) sys.error("if_all_defined macro must have at least two arguments")
    val vars = e dropRight 1
    val expr = e.last
    if (vars forall {
      case v: QueryBuilder#VarExpr => containsVar(b, v)
      case null => false
      case _ => true
    }) expr
    else null
  }

  def if_any_defined(b: QueryBuilder, e: Expr*) = {
    if (e.size < 2) sys.error("if_any_defined macro must have at least two arguments")
    val vars = e dropRight 1
    val expr = e.last
    if (vars exists {
      case v: QueryBuilder#VarExpr => containsVar(b, v)
      case null => false
      case _ => true
    }) expr
    else null
  }

  def if_all_missing(b: QueryBuilder, e: Expr*) = {
    if (e.size < 2) sys.error("if_all_missing macro must have at least two arguments")
    val vars = e dropRight 1
    val expr = e.last
    if (vars forall {
      case v: QueryBuilder#VarExpr => !containsVar(b, v)
      case null => true
      case _ => false
    }) expr
    else null
  }

  def if_any_missing(b: QueryBuilder, e: Expr*) = {
    if (e.size < 2) sys.error("if_any_missing macro must have at least two arguments")
    val vars = e dropRight 1
    val expr = e.last
    if (vars exists {
      case v: QueryBuilder#VarExpr => !containsVar(b, v)
      case null => true
      case _ => false
    }) expr
    else null
  }

  def sql_concat(b: QueryBuilder, exprs: Expr*) =
    b.SQLConcatExpr(exprs: _*)

  def ~~ (b: QueryBuilder, lop: Expr, rop: Expr) =
    b.BinExpr("~", b.FunExpr("lower", List(lop)), b.FunExpr("lower", List(rop)))

  def !~~ (b: QueryBuilder, lop: Expr, rop: Expr) =
    b.BinExpr("!~", b.FunExpr("lower", List(lop)), b.FunExpr("lower", List(rop)))

  /** Allows to specify table name as bind variable value.
   * Like {{{ []dynamic_table(:table)[deptno = 10]{dname} }}}
   * */
  def dynamic_table(b: QueryBuilder, table_name: QueryBuilder#VarExpr): b.Table = {
    b.Table(b.IdentExpr(List(String.valueOf(table_name()))), null, null, null, false)
  }

  def _lookup_edit(b: ORT,
    objName: QueryBuilder#ConstExpr,
    idName: QueryBuilder#ConstExpr,
    insertExpr: Expr,
    updateExpr: Expr) =
      b.LookupEditExpr(
        String valueOf objName.value,
        if (idName.value == null) null else String valueOf idName.value,
        insertExpr, updateExpr)

  def _insert_or_update(b: ORT,
    table: QueryBuilder#ConstExpr, insertExpr: Expr, updateExpr: Expr) =
    b.InsertOrUpdateExpr(String valueOf table.value, insertExpr, updateExpr)

  def _upsert(b: ORT, updateExpr: Expr, insertExpr: Expr) = b.UpsertExpr(updateExpr, insertExpr)

  def _delete_children(b: ORT,
    objName: QueryBuilder#ConstExpr,
    tableName: QueryBuilder#ConstExpr,
    deleteExpr: Expr) = b.DeleteChildrenExpr(
      String valueOf objName.value,
      String valueOf tableName.value,
      deleteExpr)

  def _not_delete_ids(b: ORT, idsExpr: Expr) = b.NotDeleteIdsExpr(idsExpr)

  def _id_ref_id(b: ORT,
    idRef: QueryBuilder#IdentExpr,
    id: QueryBuilder#IdentExpr) =
    b.IdRefIdExpr(idRef.name.mkString("."), id.name.mkString("."))
}
