/* Generated By:JJTree: Do not edit this line. OGroupBy.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.orient.core.sql.executor.OResult;
import com.orientechnologies.orient.core.sql.executor.OResultInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OUnwind extends SimpleNode {

  protected List<OIdentifier> items = new ArrayList<OIdentifier>();

  public OUnwind(int id) {
    super(id);
  }

  public OUnwind(OrientSql p, int id) {
    super(p, id);
  }

  /**
   * Accept the visitor.
   **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    builder.append("UNWIND ");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      items.get(i).toString(params, builder);
    }
  }

  public OUnwind copy() {
    OUnwind result = new OUnwind(-1);
    result.items = items.stream().map(x -> x.copy()).collect(Collectors.toList());
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    OUnwind oUnwind = (OUnwind) o;

    if (items != null ? !items.equals(oUnwind.items) : oUnwind.items != null)
      return false;

    return true;
  }

  @Override
  public int hashCode() {
    return items != null ? items.hashCode() : 0;
  }

  public List<OIdentifier> getItems() {
    return items;
  }

  public OResult serialize() {
    OResultInternal result = new OResultInternal();
    if (items != null) {
      result.setProperty("items", items.stream().map(x -> x.serialize()).collect(Collectors.toList()));
    }
    return result;
  }

  public void deserialize(OResult fromResult) {
    if (fromResult.getProperty("items") != null) {
      List<OResult> ser = fromResult.getProperty("items");
      items = new ArrayList<>();
      for (OResult r : ser) {
        OIdentifier exp = OIdentifier.deserialize(r);
        items.add(exp);
      }
    }
  }
}
/* JavaCC - OriginalChecksum=4739190aa6c1a3533a89b76a15bd6fdf (do not edit this line) */
