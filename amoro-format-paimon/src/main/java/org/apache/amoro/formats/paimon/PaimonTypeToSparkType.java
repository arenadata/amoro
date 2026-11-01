/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.amoro.formats.paimon;

import org.apache.paimon.types.ArrayType;
import org.apache.paimon.types.BigIntType;
import org.apache.paimon.types.BinaryType;
import org.apache.paimon.types.BooleanType;
import org.apache.paimon.types.CharType;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypeDefaultVisitor;
import org.apache.paimon.types.DateType;
import org.apache.paimon.types.DecimalType;
import org.apache.paimon.types.DoubleType;
import org.apache.paimon.types.FloatType;
import org.apache.paimon.types.IntType;
import org.apache.paimon.types.LocalZonedTimestampType;
import org.apache.paimon.types.MapType;
import org.apache.paimon.types.MultisetType;
import org.apache.paimon.types.RowType;
import org.apache.paimon.types.SmallIntType;
import org.apache.paimon.types.TimestampType;
import org.apache.paimon.types.TinyIntType;
import org.apache.paimon.types.VarBinaryType;
import org.apache.paimon.types.VarCharType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Copy from org.apache.paimon.spark.SparkTypeUtils org.apache.paimon.spark.SparkTypeUtils. Reason:
 * 1. Ams server don't have paimon-spark dependency.
 */
public class PaimonTypeToSparkType {

  private PaimonTypeToSparkType() {}

  public static DataType fromPaimonType(org.apache.paimon.types.DataType type) {
    return type.accept(PaimonToSparkTypeVisitor.INSTANCE);
  }

  private static class PaimonToSparkTypeVisitor extends DataTypeDefaultVisitor<DataType> {

    private static final PaimonToSparkTypeVisitor INSTANCE = new PaimonToSparkTypeVisitor();

    @Override
    public DataType visit(CharType charType) {
      return DataTypes.StringType;
    }

    @Override
    public DataType visit(VarCharType varCharType) {
      return DataTypes.StringType;
    }

    @Override
    public DataType visit(BooleanType booleanType) {
      return DataTypes.BooleanType;
    }

    @Override
    public DataType visit(BinaryType binaryType) {
      return DataTypes.BinaryType;
    }

    @Override
    public DataType visit(VarBinaryType varBinaryType) {
      return DataTypes.BinaryType;
    }

    @Override
    public DataType visit(DecimalType decimalType) {
      return DataTypes.createDecimalType(decimalType.getPrecision(), decimalType.getScale());
    }

    @Override
    public DataType visit(TinyIntType tinyIntType) {
      return DataTypes.ByteType;
    }

    @Override
    public DataType visit(SmallIntType smallIntType) {
      return DataTypes.ShortType;
    }

    @Override
    public DataType visit(IntType intType) {
      return DataTypes.IntegerType;
    }

    @Override
    public DataType visit(BigIntType bigIntType) {
      return DataTypes.LongType;
    }

    @Override
    public DataType visit(FloatType floatType) {
      return DataTypes.FloatType;
    }

    @Override
    public DataType visit(DoubleType doubleType) {
      return DataTypes.DoubleType;
    }

    @Override
    public DataType visit(DateType dateType) {
      return DataTypes.DateType;
    }

    @Override
    public DataType visit(TimestampType timestampType) {
      return DataTypes.TimestampType;
    }

    @Override
    public DataType visit(LocalZonedTimestampType localZonedTimestampType) {
      return DataTypes.TimestampType;
    }

    @Override
    public DataType visit(ArrayType arrayType) {
      org.apache.paimon.types.DataType elementType = arrayType.getElementType();
      return DataTypes.createArrayType(elementType.accept(this), elementType.isNullable());
    }

    @Override
    public DataType visit(MultisetType multisetType) {
      return DataTypes.createMapType(
          multisetType.getElementType().accept(this), DataTypes.IntegerType, false);
    }

    @Override
    public DataType visit(MapType mapType) {
      return DataTypes.createMapType(
          mapType.getKeyType().accept(this),
          mapType.getValueType().accept(this),
          mapType.getValueType().isNullable());
    }

    /**
     * For simplicity, as a temporary solution, we directly convert the non-null attribute to
     * nullable on the Spark side.
     */
    @Override
    public DataType visit(RowType rowType) {
      List<StructField> fields = new ArrayList<>(rowType.getFieldCount());
      for (DataField field : rowType.getFields()) {
        StructField structField =
            DataTypes.createStructField(field.name(), field.type().accept(this), true);
        structField =
            Optional.ofNullable(field.description())
                .map(structField::withComment)
                .orElse(structField);
        fields.add(structField);
      }
      return DataTypes.createStructType(fields);
    }

    @Override
    protected DataType defaultMethod(org.apache.paimon.types.DataType dataType) {
      throw new UnsupportedOperationException("Unsupported type: " + dataType);
    }
  }
}
