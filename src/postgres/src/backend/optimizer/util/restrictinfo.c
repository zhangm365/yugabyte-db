/*-------------------------------------------------------------------------
 *
 * restrictinfo.c
 *	  RestrictInfo node manipulation routines.
 *
 * Portions Copyright (c) 1996-2018, PostgreSQL Global Development Group
 * Portions Copyright (c) 1994, Regents of the University of California
 *
 *
 * IDENTIFICATION
 *	  src/backend/optimizer/util/restrictinfo.c
 *
 *-------------------------------------------------------------------------
 */
#include "postgres.h"

#include "catalog/pg_operator.h"
#include "catalog/pg_type.h"

#include "optimizer/clauses.h"
#include "optimizer/restrictinfo.h"
#include "optimizer/var.h"


static RestrictInfo *make_restrictinfo_internal(Expr *clause,
						   Expr *orclause,
						   bool is_pushed_down,
						   bool outerjoin_delayed,
						   bool pseudoconstant,
						   Index security_level,
						   Relids required_relids,
						   Relids outer_relids,
						   Relids nullable_relids);
static Expr *make_sub_restrictinfos(Expr *clause,
					   bool is_pushed_down,
					   bool outerjoin_delayed,
					   bool pseudoconstant,
					   Index security_level,
					   Relids required_relids,
					   Relids outer_relids,
					   Relids nullable_relids);


/*
 * make_restrictinfo
 *
 * Build a RestrictInfo node containing the given subexpression.
 *
 * The is_pushed_down, outerjoin_delayed, and pseudoconstant flags for the
 * RestrictInfo must be supplied by the caller, as well as the correct values
 * for security_level, outer_relids, and nullable_relids.
 * required_relids can be NULL, in which case it defaults to the actual clause
 * contents (i.e., clause_relids).
 *
 * We initialize fields that depend only on the given subexpression, leaving
 * others that depend on context (or may never be needed at all) to be filled
 * later.
 */
RestrictInfo *
make_restrictinfo(Expr *clause,
				  bool is_pushed_down,
				  bool outerjoin_delayed,
				  bool pseudoconstant,
				  Index security_level,
				  Relids required_relids,
				  Relids outer_relids,
				  Relids nullable_relids)
{
	/*
	 * If it's an OR clause, build a modified copy with RestrictInfos inserted
	 * above each subclause of the top-level AND/OR structure.
	 */
	if (or_clause((Node *) clause))
		return (RestrictInfo *) make_sub_restrictinfos(clause,
													   is_pushed_down,
													   outerjoin_delayed,
													   pseudoconstant,
													   security_level,
													   required_relids,
													   outer_relids,
													   nullable_relids);

	/* Shouldn't be an AND clause, else AND/OR flattening messed up */
	Assert(!and_clause((Node *) clause));

	return make_restrictinfo_internal(clause,
									  NULL,
									  is_pushed_down,
									  outerjoin_delayed,
									  pseudoconstant,
									  security_level,
									  required_relids,
									  outer_relids,
									  nullable_relids);
}

/*
 * make_restrictinfo_internal
 *
 * Common code for the main entry points and the recursive cases.
 */
static RestrictInfo *
make_restrictinfo_internal(Expr *clause,
						   Expr *orclause,
						   bool is_pushed_down,
						   bool outerjoin_delayed,
						   bool pseudoconstant,
						   Index security_level,
						   Relids required_relids,
						   Relids outer_relids,
						   Relids nullable_relids)
{
	RestrictInfo *restrictinfo = makeNode(RestrictInfo);

	restrictinfo->clause = clause;
	restrictinfo->orclause = orclause;
	restrictinfo->is_pushed_down = is_pushed_down;
	restrictinfo->outerjoin_delayed = outerjoin_delayed;
	restrictinfo->pseudoconstant = pseudoconstant;
	restrictinfo->can_join = false; /* may get set below */
	restrictinfo->security_level = security_level;
	restrictinfo->outer_relids = outer_relids;
	restrictinfo->nullable_relids = nullable_relids;

	/*
	 * If it's potentially delayable by lower-level security quals, figure out
	 * whether it's leakproof.  We can skip testing this for level-zero quals,
	 * since they would never get delayed on security grounds anyway.
	 */
	if (security_level > 0)
		restrictinfo->leakproof = !contain_leaked_vars((Node *) clause);
	else
		restrictinfo->leakproof = false;	/* really, "don't know" */

	/*
	 * If it's a binary opclause, set up left/right relids info. In any case
	 * set up the total clause relids info.
	 */
	if (is_opclause(clause) && list_length(((OpExpr *) clause)->args) == 2)
	{
		restrictinfo->left_relids = pull_varnos(get_leftop(clause));
		restrictinfo->right_relids = pull_varnos(get_rightop(clause));

		restrictinfo->clause_relids = bms_union(restrictinfo->left_relids,
												restrictinfo->right_relids);

		/*
		 * Does it look like a normal join clause, i.e., a binary operator
		 * relating expressions that come from distinct relations? If so we
		 * might be able to use it in a join algorithm.  Note that this is a
		 * purely syntactic test that is made regardless of context.
		 */
		if (!bms_is_empty(restrictinfo->left_relids) &&
			!bms_is_empty(restrictinfo->right_relids) &&
			!bms_overlap(restrictinfo->left_relids,
						 restrictinfo->right_relids))
		{
			restrictinfo->can_join = true;
			/* pseudoconstant should certainly not be true */
			Assert(!restrictinfo->pseudoconstant);
		}
	}
	else
	{
		/* Not a binary opclause, so mark left/right relid sets as empty */
		restrictinfo->left_relids = NULL;
		restrictinfo->right_relids = NULL;
		/* and get the total relid set the hard way */
		restrictinfo->clause_relids = pull_varnos((Node *) clause);
	}

	/* required_relids defaults to clause_relids */
	if (required_relids != NULL)
		restrictinfo->required_relids = required_relids;
	else
		restrictinfo->required_relids = restrictinfo->clause_relids;

	/*
	 * Fill in all the cacheable fields with "not yet set" markers. None of
	 * these will be computed until/unless needed.  Note in particular that we
	 * don't mark a binary opclause as mergejoinable or hashjoinable here;
	 * that happens only if it appears in the right context (top level of a
	 * joinclause list).
	 */
	restrictinfo->parent_ec = NULL;

	restrictinfo->yb_batched_rinfo = NULL;

	restrictinfo->eval_cost.startup = -1;
	restrictinfo->norm_selec = -1;
	restrictinfo->outer_selec = -1;

	restrictinfo->mergeopfamilies = NIL;

	restrictinfo->left_ec = NULL;
	restrictinfo->right_ec = NULL;
	restrictinfo->left_em = NULL;
	restrictinfo->right_em = NULL;
	restrictinfo->scansel_cache = NIL;

	restrictinfo->outer_is_left = false;

	restrictinfo->hashjoinoperator = InvalidOid;

	restrictinfo->left_bucketsize = -1;
	restrictinfo->right_bucketsize = -1;
	restrictinfo->left_mcvfreq = -1;
	restrictinfo->right_mcvfreq = -1;

	return restrictinfo;
}

/*
 *	Returns whether the given rinfo has a batched representation with
 *	an inner variable from inner_relids and its outer batched variables from
 *	outer_batched_relids.
 */
bool can_batch_rinfo(RestrictInfo *rinfo,
					 Relids outer_batched_relids,
					 Relids inner_relids)
{
	RestrictInfo *batched_rinfo = get_batched_restrictinfo(rinfo,
														   outer_batched_relids,
														   inner_relids);
	return batched_rinfo != NULL;
}

/*
 * Get a batched version of the given restrictinfo if any. The left/inner side
 * of the returned restrictinfo will have relids within inner_relids and
 * similarly for the right/outer side and outer_batched_relids.
 */
RestrictInfo *
get_batched_restrictinfo(RestrictInfo *rinfo,
						 Relids outer_batched_relids,
						 Relids inner_relids)
{
	if (list_length(rinfo->yb_batched_rinfo) == 0)
		return NULL;

	RestrictInfo *ret = linitial(rinfo->yb_batched_rinfo);
	if (!bms_is_subset(ret->left_relids, inner_relids))
	{
		/* Try the other batched rinfo if it exists. */
		if (list_length(rinfo->yb_batched_rinfo) > 1)
		{
			ret = lsecond(rinfo->yb_batched_rinfo);
		}
		else
		{
			return NULL;
		}
	}

	/*
	 * Make sure this clause involves both outer_batched_relids and
	 * inner_relids.
	 */
	if (!bms_overlap(ret->right_relids, outer_batched_relids) ||
		!bms_overlap(ret->left_relids, inner_relids))
		return NULL;

	return ret;
}

/*
 * Recursively insert sub-RestrictInfo nodes into a boolean expression.
 *
 * We put RestrictInfos above simple (non-AND/OR) clauses and above
 * sub-OR clauses, but not above sub-AND clauses, because there's no need.
 * This may seem odd but it is closely related to the fact that we use
 * implicit-AND lists at top level of RestrictInfo lists.  Only ORs and
 * simple clauses are valid RestrictInfos.
 *
 * The same is_pushed_down, outerjoin_delayed, and pseudoconstant flag
 * values can be applied to all RestrictInfo nodes in the result.  Likewise
 * for security_level, outer_relids, and nullable_relids.
 *
 * The given required_relids are attached to our top-level output,
 * but any OR-clause constituents are allowed to default to just the
 * contained rels.
 */
static Expr *
make_sub_restrictinfos(Expr *clause,
					   bool is_pushed_down,
					   bool outerjoin_delayed,
					   bool pseudoconstant,
					   Index security_level,
					   Relids required_relids,
					   Relids outer_relids,
					   Relids nullable_relids)
{
	if (or_clause((Node *) clause))
	{
		List	   *orlist = NIL;
		ListCell   *temp;

		foreach(temp, ((BoolExpr *) clause)->args)
			orlist = lappend(orlist,
							 make_sub_restrictinfos(lfirst(temp),
													is_pushed_down,
													outerjoin_delayed,
													pseudoconstant,
													security_level,
													NULL,
													outer_relids,
													nullable_relids));
		return (Expr *) make_restrictinfo_internal(clause,
												   make_orclause(orlist),
												   is_pushed_down,
												   outerjoin_delayed,
												   pseudoconstant,
												   security_level,
												   required_relids,
												   outer_relids,
												   nullable_relids);
	}
	else if (and_clause((Node *) clause))
	{
		List	   *andlist = NIL;
		ListCell   *temp;

		foreach(temp, ((BoolExpr *) clause)->args)
			andlist = lappend(andlist,
							  make_sub_restrictinfos(lfirst(temp),
													 is_pushed_down,
													 outerjoin_delayed,
													 pseudoconstant,
													 security_level,
													 required_relids,
													 outer_relids,
													 nullable_relids));
		return make_andclause(andlist);
	}
	else
		return (Expr *) make_restrictinfo_internal(clause,
												   NULL,
												   is_pushed_down,
												   outerjoin_delayed,
												   pseudoconstant,
												   security_level,
												   required_relids,
												   outer_relids,
												   nullable_relids);
}

/*
 * restriction_is_or_clause
 *
 * Returns t iff the restrictinfo node contains an 'or' clause.
 */
bool
restriction_is_or_clause(RestrictInfo *restrictinfo)
{
	if (restrictinfo->orclause != NULL)
		return true;
	else
		return false;
}

/*
 * restriction_is_securely_promotable
 *
 * Returns true if it's okay to evaluate this clause "early", that is before
 * other restriction clauses attached to the specified relation.
 */
bool
restriction_is_securely_promotable(RestrictInfo *restrictinfo,
								   RelOptInfo *rel)
{
	/*
	 * It's okay if there are no baserestrictinfo clauses for the rel that
	 * would need to go before this one, *or* if this one is leakproof.
	 */
	if (restrictinfo->security_level <= rel->baserestrict_min_security ||
		restrictinfo->leakproof)
		return true;
	else
		return false;
}

/* Simple integer comparison function. */
static int _exprcol_cmp(const void *a, const void *b, void *cxt)
{
	int a_int = ((Var *) get_leftop(*((const Expr**) a)))->varattno;
	int b_int = ((Var *) get_leftop(*((const Expr**) b)))->varattno;

	return a_int - b_int;
}

/*
 * Takes a list of batched clauses (those with clauses of the form
 * var1 = BatchedExpr(f(o_var1, o_var2...)))) and zips them up to form
 * one singular batched clause of the form
 * (var1, var2 ...) =
 * BatchedExpr(f1(o_var1, o_var2...), f2(o_var1, o_var2...)...)
 * where the LHS is sorted ascendingly by attribute number.
 */
static Expr *zip_batched_exprs(List *b_exprs)
{
	Assert(b_exprs != NIL);
	if (list_length(b_exprs) == 1)
	{
		return linitial(b_exprs);
	}

	Expr **exprcols =
		palloc(sizeof(Expr*) * list_length(b_exprs));

	int i = 0;
	ListCell *lc;
	foreach(lc, b_exprs)
	{
		Expr *b_expr = lfirst(lc);
		exprcols[i] = b_expr;
		i++;
	}

	/* Sort based on index column. */
	qsort_arg(exprcols, list_length(b_exprs), sizeof(Expr*),
			  _exprcol_cmp, NULL);

	/*
	 * v1 = BatchedExpr(f1(o)) AND v2 = BatchedExpr(f2(o))
	 * becomes ROW(v1, v2) = BatchedExpr(ROW(f1(o),f2(o)))
	 */

	RowExpr *leftop = makeNode(RowExpr);
	RowExpr *rightop = makeNode(RowExpr);

	Oid opresulttype = -1;
	bool opretset = false;
	Oid opcolloid = -1;
	Oid inputcollid = -1;

	for (i = 0; i < list_length(b_exprs); i++)
	{
		Expr *b_expr = (Expr *) exprcols[i];
		OpExpr *opexpr = (OpExpr *) b_expr;
		opresulttype = opexpr->opresulttype;
		opretset = opexpr->opretset;
		opcolloid = opexpr->opcollid;
		inputcollid = opexpr->inputcollid;

		Var *left_var = (Var *) get_leftop(b_expr);
		leftop->args = lappend(leftop->args, left_var);

		Expr *right_expr =
			(Expr *) ((YbBatchedExpr *) get_rightop(b_expr))->orig_expr;
		rightop->args = lappend(rightop->args, right_expr);
	}

	pfree(exprcols);

	leftop->colnames = NIL;
	leftop->row_format = COERCE_EXPLICIT_CALL;
	leftop->row_typeid = RECORDOID;

	rightop->colnames = NIL;
	rightop->row_format = COERCE_EXPLICIT_CALL;
	rightop->row_typeid = RECORDOID;

	YbBatchedExpr *right_batched_expr = makeNode(YbBatchedExpr);
	right_batched_expr->orig_expr = (Expr*) rightop;

	return (Expr*) make_opclause(RECORD_EQ_OP, opresulttype, opretset,
						 		 (Expr*) leftop, (Expr*) right_batched_expr,
						 		 opcolloid, inputcollid);
}

/* 
 * Add a given batched RestrictInfo to rinfo if it hasn't already been added.
 */
void add_batched_rinfo(RestrictInfo *rinfo, RestrictInfo *batched)
{
	ListCell *lc;
	foreach(lc, rinfo->yb_batched_rinfo)
	{
		RestrictInfo *current = lfirst(lc);
		/* If we already have a batched clause with this LHS we don't bother.*/
		if (equal(get_leftop(current->clause), get_leftop(batched->clause)))
			return;	
	}

	rinfo->yb_batched_rinfo = lappend(rinfo->yb_batched_rinfo, batched);
}


List *
yb_get_actual_batched_clauses(PlannerInfo *root,
										List *restrictinfo_list,
										Path *inner_path)
{
	Relids 		batchedrelids = root->yb_cur_batched_relids;
	List	   *result = NIL;
	ListCell   *l;
	ListCell   *lc;

	Relids inner_req_rels = PATH_REQ_OUTER(inner_path);

	/*
	 * We only zip up clauses involving outer relations A and B if they can
	 * be found under the same element in yb_availBatchedRelids.
	 */
	
	Relids cumulative_rels = NULL;
	foreach(lc, root->yb_availBatchedRelids)
	{
		Relids cur_relgroup = (Relids) lfirst(lc);

		/* Check if clause will be even relevant to this relgroup. */
		if (!bms_overlap(cur_relgroup, inner_req_rels))
			continue;

		/* Check to make sure we haven't already seen these rels. */
		if (bms_is_subset(cur_relgroup, cumulative_rels))
			continue;

		Assert(!bms_overlap(cur_relgroup, cumulative_rels));

		cumulative_rels = bms_add_members(cumulative_rels, cur_relgroup);
		List *batched_list = NIL;
		List *batched_rinfos = NIL;

		Index security_level;
		Relids required_relids = NULL;
		Relids outer_relids = NULL;
		Relids nullable_relids = NULL;

		foreach(l, restrictinfo_list)
		{
			RestrictInfo *rinfo = lfirst_node(RestrictInfo, l);
			security_level = rinfo->security_level;
			RestrictInfo *tmp_batched =
				get_batched_restrictinfo(rinfo,
					batchedrelids,
					inner_path->parent->relids);

			if (tmp_batched)
			{
				if (!bms_overlap(tmp_batched->clause_relids, cur_relgroup))
					continue;

				batched_list =
					list_append_unique_ptr(batched_list, tmp_batched->clause);
				required_relids =
					bms_union(required_relids, tmp_batched->required_relids);
				outer_relids =
					bms_union(outer_relids, tmp_batched->outer_relids);
				nullable_relids =
					bms_union(nullable_relids, tmp_batched->nullable_relids);
				batched_rinfos = lappend(batched_rinfos, rinfo);
				continue;
			}

			Assert(!rinfo->pseudoconstant);

			result = lappend(result, rinfo->clause);
		}

		if (!batched_list)
		{
			continue;
		}

		Expr *zipped = zip_batched_exprs(batched_list);
		result = lappend(result, zipped);

		/* This is needed to make this method idempotent. */
		if (list_length(batched_rinfos) == 1)
		{
			bms_free(required_relids);
			bms_free(outer_relids);
			bms_free(nullable_relids);
			continue;
		}

		RestrictInfo *zipped_rinfo =
			make_restrictinfo(zipped, false, false, false, security_level,
							required_relids, outer_relids, nullable_relids);

		foreach(l, batched_rinfos)
		{
			RestrictInfo *rinfo = lfirst_node(RestrictInfo, l);
			list_free(rinfo->yb_batched_rinfo);
			rinfo->yb_batched_rinfo = list_make1(zipped_rinfo);
		}

		bms_free(required_relids);
		bms_free(outer_relids);
		bms_free(nullable_relids);
	}

	return result;
}

/*
 * get_actual_clauses
 *
 * Returns a list containing the bare clauses from 'restrictinfo_list'.
 *
 * This is only to be used in cases where none of the RestrictInfos can
 * be pseudoconstant clauses (for instance, it's OK on indexqual lists).
 */
List *
get_actual_clauses(List *restrictinfo_list)
{
	List	   *result = NIL;
	ListCell   *l;

	foreach(l, restrictinfo_list)
	{
		RestrictInfo *rinfo = lfirst_node(RestrictInfo, l);

		Assert(!rinfo->pseudoconstant);

		result = lappend(result, rinfo->clause);
	}
	return result;
}

/*
 * extract_actual_clauses
 *
 * Extract bare clauses from 'restrictinfo_list', returning either the
 * regular ones or the pseudoconstant ones per 'pseudoconstant'.
 */
List *
extract_actual_clauses(List *restrictinfo_list,
					   bool pseudoconstant)
{
	List	   *result = NIL;
	ListCell   *l;

	foreach(l, restrictinfo_list)
	{
		RestrictInfo *rinfo = lfirst_node(RestrictInfo, l);

		if (rinfo->pseudoconstant == pseudoconstant)
			result = lappend(result, rinfo->clause);
	}
	return result;
}

/*
 * extract_actual_join_clauses
 *
 * Extract bare clauses from 'restrictinfo_list', separating those that
 * semantically match the join level from those that were pushed down.
 * Pseudoconstant clauses are excluded from the results.
 *
 * This is only used at outer joins, since for plain joins we don't care
 * about pushed-down-ness.
 */
void
extract_actual_join_clauses(List *restrictinfo_list,
							Relids joinrelids,
							List **joinquals,
							List **otherquals)
{
	ListCell   *l;

	*joinquals = NIL;
	*otherquals = NIL;

	foreach(l, restrictinfo_list)
	{
		RestrictInfo *rinfo = lfirst_node(RestrictInfo, l);

		if (RINFO_IS_PUSHED_DOWN(rinfo, joinrelids))
		{
			if (!rinfo->pseudoconstant)
				*otherquals = lappend(*otherquals, rinfo->clause);
		}
		else
		{
			/* joinquals shouldn't have been marked pseudoconstant */
			Assert(!rinfo->pseudoconstant);
			*joinquals = lappend(*joinquals, rinfo->clause);
		}
	}
}


/*
 * join_clause_is_movable_to
 *		Test whether a join clause is a safe candidate for parameterization
 *		of a scan on the specified base relation.
 *
 * A movable join clause is one that can safely be evaluated at a rel below
 * its normal semantic level (ie, its required_relids), if the values of
 * variables that it would need from other rels are provided.
 *
 * We insist that the clause actually reference the target relation; this
 * prevents undesirable movement of degenerate join clauses, and ensures
 * that there is a unique place that a clause can be moved down to.
 *
 * We cannot move an outer-join clause into the non-nullable side of its
 * outer join, as that would change the results (rows would be suppressed
 * rather than being null-extended).
 *
 * Also there must not be an outer join below the clause that would null the
 * Vars coming from the target relation.  Otherwise the clause might give
 * results different from what it would give at its normal semantic level.
 *
 * Also, the join clause must not use any relations that have LATERAL
 * references to the target relation, since we could not put such rels on
 * the outer side of a nestloop with the target relation.
 */
bool
join_clause_is_movable_to(RestrictInfo *rinfo, RelOptInfo *baserel)
{
	/* Clause must physically reference target rel */
	if (!bms_is_member(baserel->relid, rinfo->clause_relids))
		return false;

	/* Cannot move an outer-join clause into the join's outer side */
	if (bms_is_member(baserel->relid, rinfo->outer_relids))
		return false;

	/* Target rel must not be nullable below the clause */
	if (bms_is_member(baserel->relid, rinfo->nullable_relids))
		return false;

	/* Clause must not use any rels with LATERAL references to this rel */
	if (bms_overlap(baserel->lateral_referencers, rinfo->clause_relids))
		return false;

	return true;
}

/*
 * join_clause_is_movable_into
 *		Test whether a join clause is movable and can be evaluated within
 *		the current join context.
 *
 * currentrelids: the relids of the proposed evaluation location
 * current_and_outer: the union of currentrelids and the required_outer
 *		relids (parameterization's outer relations)
 *
 * The API would be a bit clearer if we passed the current relids and the
 * outer relids separately and did bms_union internally; but since most
 * callers need to apply this function to multiple clauses, we make the
 * caller perform the union.
 *
 * Obviously, the clause must only refer to Vars available from the current
 * relation plus the outer rels.  We also check that it does reference at
 * least one current Var, ensuring that the clause will be pushed down to
 * a unique place in a parameterized join tree.  And we check that we're
 * not pushing the clause into its outer-join outer side, nor down into
 * a lower outer join's inner side.
 *
 * The check about pushing a clause down into a lower outer join's inner side
 * is only approximate; it sometimes returns "false" when actually it would
 * be safe to use the clause here because we're still above the outer join
 * in question.  This is okay as long as the answers at different join levels
 * are consistent: it just means we might sometimes fail to push a clause as
 * far down as it could safely be pushed.  It's unclear whether it would be
 * worthwhile to do this more precisely.  (But if it's ever fixed to be
 * exactly accurate, there's an Assert in get_joinrel_parampathinfo() that
 * should be re-enabled.)
 *
 * There's no check here equivalent to join_clause_is_movable_to's test on
 * lateral_referencers.  We assume the caller wouldn't be inquiring unless
 * it'd verified that the proposed outer rels don't have lateral references
 * to the current rel(s).  (If we are considering join paths with the outer
 * rels on the outside and the current rels on the inside, then this should
 * have been checked at the outset of such consideration; see join_is_legal
 * and the path parameterization checks in joinpath.c.)  On the other hand,
 * in join_clause_is_movable_to we are asking whether the clause could be
 * moved for some valid set of outer rels, so we don't have the benefit of
 * relying on prior checks for lateral-reference validity.
 *
 * Note: if this returns true, it means that the clause could be moved to
 * this join relation, but that doesn't mean that this is the lowest join
 * it could be moved to.  Caller may need to make additional calls to verify
 * that this doesn't succeed on either of the inputs of a proposed join.
 *
 * Note: get_joinrel_parampathinfo depends on the fact that if
 * current_and_outer is NULL, this function will always return false
 * (since one or the other of the first two tests must fail).
 */
bool
join_clause_is_movable_into(RestrictInfo *rinfo,
							Relids currentrelids,
							Relids current_and_outer)
{
	/* Clause must be evaluable given available context */
	if (!bms_is_subset(rinfo->clause_relids, current_and_outer))
		return false;

	/* Clause must physically reference at least one target rel */
	if (!bms_overlap(currentrelids, rinfo->clause_relids))
		return false;

	/* Cannot move an outer-join clause into the join's outer side */
	if (bms_overlap(currentrelids, rinfo->outer_relids))
		return false;

	/*
	 * Target rel(s) must not be nullable below the clause.  This is
	 * approximate, in the safe direction, because the current join might be
	 * above the join where the nulling would happen, in which case the clause
	 * would work correctly here.  But we don't have enough info to be sure.
	 */
	if (bms_overlap(currentrelids, rinfo->nullable_relids))
		return false;

	return true;
}
