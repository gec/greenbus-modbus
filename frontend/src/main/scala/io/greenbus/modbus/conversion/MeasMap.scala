/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.modbus.conversion

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.service.proto.Measurements.{ Measurement, Quality }
import io.greenbus.client.service.proto.Measurements
import io.greenbus.modbus.conversion.MeasMap.ByteX4
import io.greenbus.modbus.xml._
import org.totalgrid.modbus.{ ByteX2, ModbusData }
import org.totalgrid.modbus.ModbusBit
import org.totalgrid.modbus.ModbusRegister

object MeasMap extends LazyLogging {

  val good = Quality.newBuilder.setValidity(Quality.Validity.GOOD).build

  def boolListToMap(list: Traversable[BooleanValue]): Map[Int, Traversable[BooleanValue]] = list.groupBy(_.getIndex).toMap
  def numListToMap(list: Traversable[NumericValue]): Map[Int, Traversable[NumericValue]] = list.groupBy(_.getIndex).toMap

  def create(): Measurement.Builder = {
    Measurements.Measurement.newBuilder.setQuality(good)
  }

  def build(value: Boolean) =
    create().setType(Measurements.Measurement.Type.BOOL).setBoolVal(value).build()

  def build(value: Long) =
    create().setType(Measurements.Measurement.Type.INT).setIntVal(value).build()

  def build(value: Double) =
    create().setType(Measurements.Measurement.Type.DOUBLE).setDoubleVal(value).build()

  case class ByteX4(first: ByteX2, second: ByteX2)

  def parseBitMask(s: String): Long = {
    val trimmed = s.trim
    if (trimmed.startsWith("0x")) {
      java.lang.Long.parseLong(trimmed.drop(2), 16)
    } else {
      java.lang.Long.parseLong(trimmed)
    }
  }
}

class RegisterMap(registers: Traversable[ModbusRegister]) {
  private val map = registers.map(x => (x.index, x.value)).toMap

  def get(i: Int): Option[ByteX2] = map.get(i)
  def get32(i: Int): Option[ByteX4] = for (x <- map.get(i); y <- map.get(i + 1)) yield ByteX4(x, y)
  def get64(i: Int): Option[IndexedSeq[ByteX2]] = {
    for {
      v1 <- map.get(i)
      v2 <- map.get(i + 1)
      v3 <- map.get(i + 2)
      v4 <- map.get(i + 3)
    } yield {
      Vector(v1, v2, v3, v4)
    }
  }
}

import MeasMap._

class MeasMap(di: Traversable[DiscreteInputMap.Mapping], cs: Traversable[CoilStatusMap.Mapping], ir: Traversable[InputRegisterMap.Mapping], hr: Traversable[HoldingRegisterMap.Mapping]) extends LazyLogging {

  def convertDiscreteInput(list: Traversable[ModbusBit]): Traversable[(String, Measurement)] = {
    handleBinary(list, di)
  }
  def convertCoilStatus(list: Traversable[ModbusBit]): Traversable[(String, Measurement)] = {
    handleBinary(list, cs)
  }

  private def handleBinary(list: Traversable[ModbusBit], mappings: Traversable[BooleanValue]) = {
    val map: Map[Int, Boolean] = list.map(mb => (mb.index, mb.value)).toMap
    mappings.flatMap { m =>
      map.get(m.getIndex).map(v => (m.getName, build(v)))
    }
  }

  def convertInputRegister(list: Traversable[ModbusRegister]): Traversable[(String, Measurement)] = {
    handleNumeric(list, ir)
  }

  def convertHoldingRegister(list: Traversable[ModbusRegister]): Traversable[(String, Measurement)] = {
    handleNumeric(list, hr)
  }

  private def handleNumeric(list: Traversable[ModbusRegister], mappings: Traversable[NumericValue]): Traversable[(String, Measurement)] = {
    val regMap = new RegisterMap(list)
    mappings.flatMap { m =>
      val i = m.getIndex
      val maskOpt = Option(m.getBitMask).map(MeasMap.parseBitMask)
      val shiftRightOpt = Option(m.getShiftRight).map(_.toInt)

      def handleMask(raw: Long): Long = {
        maskOpt match {
          case None => raw
          case Some(mask) =>
            val masked = raw & mask
            shiftRightOpt match {
              case None => masked
              case Some(shift) => masked >> shift
            }
        }
      }

      val measOpt: Option[Measurement] = m.getType match {
        case Conversion.S_INT_16 => regMap.get(i).map(r => build(r.sInt16))
        case Conversion.U_INT_16 => regMap.get(i).map(r => build(handleMask(r.uInt16)))
        case Conversion.S_INT_32_LE => regMap.get32(i).map(r => build(ModbusData.joinSInt32LE(r.first, r.second)))
        case Conversion.S_INT_32_BE => regMap.get32(i).map(r => build(ModbusData.joinSInt32BE(r.first, r.second)))
        case Conversion.U_INT_32_LE => regMap.get32(i).map(r => build(handleMask(ModbusData.joinUInt32LE(r.first, r.second))))
        case Conversion.U_INT_32_BE => regMap.get32(i).map(r => build(handleMask(ModbusData.joinUInt32BE(r.first, r.second))))
        case Conversion.FLOAT_32_LE => regMap.get32(i).map(r => build(ModbusData.joinFloat32LE(r.first, r.second)))
        case Conversion.FLOAT_32_BE => regMap.get32(i).map(r => build(ModbusData.joinFloat32BE(r.first, r.second)))
        case Conversion.FLOAT_64_LE => regMap.get64(i).map(r => build(ModbusData.joinFloat64LE(r(0), r(1), r(2), r(3))))
        case Conversion.FLOAT_64_BE => regMap.get64(i).map(r => build(ModbusData.joinFloat64BE(r(0), r(1), r(2), r(3))))
        case _ =>
          logger.warn("Unknown toNumber: " + m.getType + " on index: " + i + " name: " + m.getName)
          None
      }

      measOpt.map(meas => (m.getName, meas))
    }
  }
}
