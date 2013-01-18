package org.saiku.olap.query2.util;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.olap4j.Axis;
import org.olap4j.OlapException;
import org.olap4j.metadata.Cube;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Measure;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinCalculatedMeasure;
import org.saiku.olap.query2.ThinDetails;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMeasure;
import org.saiku.olap.query2.ThinMeasure.Type;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinQueryModel.AxisLocation;
import org.saiku.olap.query2.common.ThinQuerySet;
import org.saiku.olap.query2.common.ThinSortableQuerySet;
import org.saiku.olap.query2.filter.ThinFilter;
import org.saiku.query.IQuerySet;
import org.saiku.query.ISortableQuerySet;
import org.saiku.query.Query;
import org.saiku.query.QueryAxis;
import org.saiku.query.QueryDetails.Location;
import org.saiku.query.QueryHierarchy;
import org.saiku.query.QueryLevel;
import org.saiku.query.mdx.GenericFilter;
import org.saiku.query.mdx.IFilterFunction;
import org.saiku.query.mdx.IFilterFunction.MdxFunctionType;
import org.saiku.query.mdx.NFilter;
import org.saiku.query.mdx.NameFilter;
import org.saiku.query.mdx.NameLikeFilter;
import org.saiku.query.metadata.CalculatedMeasure;

public class Fat {
	
	public static Query convert(ThinQuery query, Cube cube) throws SQLException {
		ThinQueryModel model = query.getQueryModel();
		Query q = new Query(query.getName(), cube);
		
		convertAxes(q, query.getQueryModel().getAxes());
		convertCalculatedMeasures(q, model.getCalculatedMeasures());
		convertDetails(q, model.getDetails());
		q.setVisualTotals(model.isVisualTotals());
		q.setVisualTotalsPattern(model.getVisualTotalsPattern());
		return q;
	}

	private static void convertCalculatedMeasures(Query q, List<ThinCalculatedMeasure> thinCms) {
		if (thinCms != null && thinCms.size() > 0) {
			for (ThinCalculatedMeasure qcm : thinCms) {
				// TODO improve this
				Hierarchy h = q.getCube().getMeasures().get(0).getHierarchy();
				
				CalculatedMeasure cm = 
						new CalculatedMeasure(
								h, 
								qcm.getName(), 
								null, 
								qcm.getFormula(),
								qcm.getProperties());
				
				q.addCalculatedMeasure(cm);
			}
		}
	}

	private static void convertDetails(Query query, ThinDetails details) {
		Location loc = Location.valueOf(details.getLocation().toString());
		query.getDetails().setLocation(loc);
		Axis ax = getLocation(details.getAxis());
		query.getDetails().setAxis(ax);
		
		if (details != null && details.getMeasures().size() > 0) {
			for (ThinMeasure m : details.getMeasures()) {
				if (Type.CALCULATED.equals(m.getType())) {
					Measure measure = query.getCalculatedMeasure(m.getName());
					query.getDetails().add(measure);
				} else if (Type.EXACT.equals(m.getType())) {
					Measure measure = query.getMeasure(m.getName());
					query.getDetails().add(measure);
				}
			}
		}
	}

	private static void convertAxes(Query q, Map<AxisLocation, ThinAxis> axes) throws OlapException {
		if (axes != null) {
			for (AxisLocation axis : axes.keySet()) {
				if (axis != null) {
					convertAxis(q, axes.get(axis));
				}
			}
		}
	}

	private static void convertAxis(Query query, ThinAxis thinAxis) throws OlapException {
		Axis loc = getLocation(thinAxis.getLocation());
		QueryAxis qaxis = query.getAxis(loc);
		for (ThinHierarchy hierarchy : thinAxis.getHierarchies().values()) {
			QueryHierarchy qh = query.getHierarchy(hierarchy.getName());
			if (qh != null) {
				convertHierarchy(qh, hierarchy);
				qaxis.addHierarchy(qh);
			}
		}
		qaxis.setNonEmpty(thinAxis.isNonEmpty());
		extendSortableQuerySet(query, qaxis, thinAxis);
	}
	
	private static void convertHierarchy(QueryHierarchy qh, ThinHierarchy th) throws OlapException {
		for (ThinLevel tl : th.getLevels().values()) {
			QueryLevel ql = qh.includeLevel(tl.getName());
			for (ThinMember tm : tl.getInclusions()) {
				qh.includeMember(tm.getUniqueName());
			}
			for (ThinMember tm : tl.getExclusions()) {
				qh.excludeMember(tm.getUniqueName());
			}
			extendQuerySet(qh.getQuery(), ql, tl);
		}
		extendSortableQuerySet(qh.getQuery(), qh, th);
	}


	private static Axis getLocation(AxisLocation axis) {
		String ax = axis.toString();
		if (AxisLocation.ROWS.toString().equals(ax)) {
			return Axis.ROWS;
		} else if (AxisLocation.COLUMNS.toString().equals(ax)) {
			return Axis.COLUMNS;
		} else if (AxisLocation.FILTER.toString().equals(ax)) {
			return Axis.FILTER;
		} else if (AxisLocation.PAGES.toString().equals(ax)) {
			return Axis.PAGES;
		}
		return null;
	}

	private static void extendQuerySet(Query q, IQuerySet qs, ThinQuerySet ts) {
		qs.setMdxSetExpression(ts.getMdx());
		
		if (ts.getFilters() != null && ts.getFilters().size() > 0) {
			List<IFilterFunction> filters = convertFilters(q, ts.getFilters());
			qs.getFilters().addAll(filters);
		}
		
	}
	
	private static List<IFilterFunction> convertFilters(Query q, List<ThinFilter> filters) {
		List<IFilterFunction> qfs = new ArrayList<IFilterFunction>();
		for (ThinFilter f : filters) {
			switch(f.getFlavour()) {
				case Name:
					List<String> exp = f.getExpressions();
					if (exp != null && exp.size() > 1) {
						String hierarchyUniqueName = exp.remove(0);
						QueryHierarchy qh = q.getHierarchy(hierarchyUniqueName);
						NameFilter nf = new NameFilter(qh.getHierarchy(), exp);
						qfs.add(nf);
					}
					break;
				case NameLike:
					List<String> exp2 = f.getExpressions();
					if (exp2 != null && exp2.size() > 1) {
						String hierarchyUniqueName = exp2.remove(0);
						QueryHierarchy qh = q.getHierarchy(hierarchyUniqueName);
						NameLikeFilter nf = new NameLikeFilter(qh.getHierarchy(), exp2);
						qfs.add(nf);
					}
					break;
				case Generic:
					List<String> gexp = f.getExpressions();
					if (gexp != null && gexp.size() == 1) {
						GenericFilter gf = new GenericFilter(gexp.get(0));
						qfs.add(gf);
					}
					break;
				case Measure:
					// TODO Implement this
					break;
				case N:
					List<String> nexp = f.getExpressions();
					if (nexp != null && nexp.size() > 1) {
						MdxFunctionType mf = MdxFunctionType.valueOf(f.getFunction().toString());
						int n = Integer.parseInt(nexp.get(0));
						String expression = nexp.get(1);
						NFilter nf = new NFilter(mf, n, expression);
						qfs.add(nf);
					}
					break;
				default:
					break;
			}
		}
		return qfs;
	}

	private static void extendSortableQuerySet(Query q, ISortableQuerySet qs, ThinSortableQuerySet ts) {
		extendQuerySet(q, qs, ts);
		if (ts.getHierarchizeMode() != null) {
			qs.setHierarchizeMode(org.saiku.query.ISortableQuerySet.HierarchizeMode.valueOf(ts.getHierarchizeMode().toString()));
		}
		if (ts.getSortOrder() != null) {
			qs.sort(org.saiku.query.SortOrder.valueOf(ts.getSortOrder().toString()), ts.getSortEvaluationLiteral());
		}
		
		
	}
	

}