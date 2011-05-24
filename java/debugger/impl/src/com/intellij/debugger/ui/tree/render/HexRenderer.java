/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.debugger.ui.tree.render;

import com.intellij.debugger.DebuggerContext;
import com.intellij.debugger.engine.evaluation.EvaluationContext;
import com.intellij.debugger.ui.tree.DebuggerTreeNode;
import com.intellij.debugger.ui.tree.NodeDescriptor;
import com.intellij.debugger.ui.tree.ValueDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiExpression;
import com.sun.jdi.*;
import org.jetbrains.annotations.NonNls;

/**
 * User: lex
 * Date: Nov 19, 2003
 * Time: 3:13:11 PM
 */
public class HexRenderer extends NodeRendererImpl{
  public static final @NonNls String UNIQUE_ID = "HexRenderer";
  private static final Logger LOG = Logger.getInstance("#com.intellij.debugger.ui.tree.render.HexRenderer");

  public HexRenderer() {
    setEnabled(false);
  }

  public String getUniqueId() {
    return UNIQUE_ID;
  }

  public @NonNls String getName() {
    return "Hex";
  }

  public void setName(String name) {
    // prohibit change
  }

  public HexRenderer clone() {
    return (HexRenderer) super.clone();
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  public String calcLabel(ValueDescriptor valueDescriptor, EvaluationContext evaluationContext, DescriptorLabelListener labelListener) {
    Value value = valueDescriptor.getValue();
    StringBuilder buf = new StringBuilder(16);

    if(value == null) {
      buf.append("null");
    }
    else if (value instanceof CharValue) {
      buf.append("'");
      buf.append(value.toString());
      buf.append("' ");
      long longValue = ((PrimitiveValue)value).longValue();
      buf.append("0x").append(Long.toHexString(longValue).toUpperCase());
    }
    else if (value instanceof ByteValue) {
      byte val = ((PrimitiveValue)value).byteValue();
      String strValue = Integer.toHexString(val).toUpperCase();
      if (strValue.length() > 2) {
        strValue = strValue.substring(strValue.length() - 2);
      }
      buf.append("0x").append(strValue);
    }
    else if (value instanceof ShortValue) {
      short val = ((PrimitiveValue)value).shortValue();
      String strValue = Integer.toHexString(val).toUpperCase();
      if (strValue.length() > 4) {
        strValue = strValue.substring(strValue.length() - 4);
      }
      buf.append("0x").append(strValue);
    }
    else if (value instanceof IntegerValue) {
      int val = ((PrimitiveValue)value).intValue();
      buf.append("0x").append(Integer.toHexString(val).toUpperCase());
    }
    else if (value instanceof LongValue) {
      long val = ((PrimitiveValue)value).longValue();
      buf.append("0x").append(Long.toHexString(val).toUpperCase());
    }
    else {
      LOG.assertTrue(false);
    }
    return buf.toString();
  }

  public void buildChildren(Value value, ChildrenBuilder builder, EvaluationContext evaluationContext) {
  }

  //returns whether this renderer is apllicable to this type or it's supertypes
  public boolean isApplicable(Type t) {
    if(t == null) {
      return false;
    }
    return t instanceof CharType ||
           t instanceof ByteType ||
           t instanceof ShortType ||
           t instanceof IntegerType ||
           t instanceof LongType;
  }

  public PsiExpression getChildValueExpression(DebuggerTreeNode node, DebuggerContext context) {
    LOG.assertTrue(false);
    return null;
  }

  public boolean isExpandable(Value value, EvaluationContext evaluationContext, NodeDescriptor parentDescriptor) {
    return false;
  }

}
