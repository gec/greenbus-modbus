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

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.totalgrid.modbus.{ ByteX2, ModbusRegister }
import io.greenbus.client.service.proto.Measurements.Measurement
import io.greenbus.modbus.xml.{ HoldingRegisterMap, InputRegisterMap, Conversion }

@RunWith(classOf[JUnitRunner])
class MeasMapTestSuite extends FunSuite with ShouldMatchers {

  test("Converts unsigned 16bit inputs registers") {
    val mm = new MeasMap(Nil, Nil, List(XmlGenerator.toNumber(new InputRegisterMap.Mapping, 0, "toNum", Conversion.U_INT_16)), Nil)
    val meas = mm.convertInputRegister(List(ModbusRegister(0, ByteX2("FFFF"))))
    meas.size should equal(1)
    meas.head._2.getIntVal should equal(65535)
  }

  test("Converts signed 16bit holding registers") {
    val conversions = List(XmlGenerator.toNumber(new HoldingRegisterMap.Mapping, 1, "toNum", Conversion.S_INT_16))
    val mm = new MeasMap(Nil, Nil, Nil, conversions)
    val list = List(ModbusRegister(1, ByteX2("FFFF")))
    val meas = mm.convertHoldingRegister(list)
    meas.size should equal(1)
    meas.head._2.getIntVal should equal(-1)
  }

  test("Converts unsigned 32bit holding registers") {
    val conversions = List(XmlGenerator.toNumber(new HoldingRegisterMap.Mapping, 1, "toNum", Conversion.U_INT_32_BE))
    val mm = new MeasMap(Nil, Nil, Nil, conversions)
    val list = List(ModbusRegister(1, ByteX2("FFFF")), ModbusRegister(2, ByteX2("0000")))
    val meas = mm.convertHoldingRegister(list)
    meas.size should equal(1)
    meas.head._2.getIntVal should equal(4294901760L)
  }

  test("Converts floats") {
    val conversions = List(XmlGenerator.toNumber(new HoldingRegisterMap.Mapping, 1, "toNum", Conversion.FLOAT_32_BE))
    val mm = new MeasMap(Nil, Nil, Nil, conversions)
    // 0x40490FD0 == 3.14159
    val list = List(ModbusRegister(1, ByteX2("4049")), ModbusRegister(2, ByteX2("0FD0")))
    val meas = mm.convertHoldingRegister(list)
    meas.size should equal(1)
    meas.head._2.getDoubleVal() should be(3.14159 plusOrMinus 1e-6)
  }

  test("Converts doubles") {
    val conversions = List(XmlGenerator.toNumber(new HoldingRegisterMap.Mapping, 1, "toNum", Conversion.FLOAT_64_BE))
    val mm = new MeasMap(Nil, Nil, Nil, conversions)
    // 0x400921F9F01B866E == 3.1415900000000000
    val list = Seq(
      ModbusRegister(1, ByteX2("4009")),
      ModbusRegister(2, ByteX2("21F9")),
      ModbusRegister(3, ByteX2("F01B")),
      ModbusRegister(4, ByteX2("866E")))
    val meas = mm.convertHoldingRegister(list)
    meas.size should equal(1)
    meas.head._2.getDoubleVal() should be(3.14159 plusOrMinus 1e-6)
  }

  def testIntVal(f: Traversable[ModbusRegister] => Traversable[(String, Measurement)], regs: Seq[ModbusRegister], shouldBe: Int): Unit = {
    val meas = f(regs)
    meas.size should equal(1)
    meas.head._2.getIntVal should equal(shouldBe)
  }

  test("Masking, 16bit") {
    val mm = new MeasMap(Nil, Nil, List(XmlGenerator.toNumber(new InputRegisterMap.Mapping, 0, "toNum", Conversion.U_INT_16, Some("0x04"))), Nil)
    testIntVal(mm.convertInputRegister, Seq(ModbusRegister(0, ByteX2("FFFF"))), 4)
    testIntVal(mm.convertInputRegister, Seq(ModbusRegister(0, ByteX2("FFFB"))), 0)
  }
  test("Masking, 16bit, definitely hex") {
    val mm = new MeasMap(Nil, Nil, List(XmlGenerator.toNumber(new InputRegisterMap.Mapping, 0, "toNum", Conversion.U_INT_16, Some("0x20"))), Nil)
    testIntVal(mm.convertInputRegister, Seq(ModbusRegister(0, ByteX2("FFFF"))), 32)
    testIntVal(mm.convertInputRegister, Seq(ModbusRegister(0, ByteX2("FFDF"))), 0)
  }
  test("Masking, 32bit") {
    val mm = new MeasMap(Nil, Nil, Nil, List(XmlGenerator.toNumber(new HoldingRegisterMap.Mapping, 1, "toNum", Conversion.U_INT_32_BE, Some("0x04"))))
    testIntVal(mm.convertHoldingRegister, Seq(ModbusRegister(1, ByteX2("FFFF")), ModbusRegister(2, ByteX2("FFFF"))), 4)
    testIntVal(mm.convertHoldingRegister, Seq(ModbusRegister(1, ByteX2("FFFF")), ModbusRegister(2, ByteX2("FFFB"))), 0)
  }

  test("Masking, 16bit, multi meas") {
    val mm = new MeasMap(
      Nil,
      Nil,
      Seq(
        XmlGenerator.toNumberByName(new InputRegisterMap.Mapping, 0, "meas01", Conversion.U_INT_16, Some("0x02")),
        XmlGenerator.toNumberByName(new InputRegisterMap.Mapping, 0, "meas02", Conversion.U_INT_16, Some("0x04"))),
      Nil)
    val meas = mm.convertInputRegister(List(ModbusRegister(0, ByteX2("FFFF"))))
    meas.size should equal(2)
    val resultSet = meas.map { case (name, m) => (name, m.getIntVal) }.toSet
    resultSet should equal(Set(("meas01", 2), ("meas02", 4)))
  }

  test("Masking, 16bit, multi meas with shifting") {
    val mm = new MeasMap(
      Nil,
      Nil,
      Seq(
        XmlGenerator.toNumberByName(new InputRegisterMap.Mapping, 0, "meas01", Conversion.U_INT_16, Some("0xE000"), Some(13)),
        XmlGenerator.toNumberByName(new InputRegisterMap.Mapping, 0, "meas02", Conversion.U_INT_16, Some("0x1000"), Some(12)),
        XmlGenerator.toNumberByName(new InputRegisterMap.Mapping, 0, "meas03", Conversion.U_INT_16, Some("0x800"), Some(11)),
        XmlGenerator.toNumberByName(new InputRegisterMap.Mapping, 0, "meas04", Conversion.U_INT_16, Some("0x600"), Some(9)),
        XmlGenerator.toNumberByName(new InputRegisterMap.Mapping, 0, "meas05", Conversion.U_INT_16, Some("0x100"), Some(8)),
        XmlGenerator.toNumberByName(new InputRegisterMap.Mapping, 0, "meas06", Conversion.U_INT_16, Some("0xFF"), Some(0))),
      Nil)

    {
      val meas = mm.convertInputRegister(List(ModbusRegister(0, ByteX2("1A22"))))
      meas.size should equal(6)
      val resultSet = meas.map { case (name, m) => (name, m.getIntVal) }.toSet
      resultSet should equal(
        Set(("meas01", 0),
          ("meas02", 1),
          ("meas03", 1),
          ("meas04", 1),
          ("meas05", 0),
          ("meas06", 34)))
    }

    {
      val meas = mm.convertInputRegister(List(ModbusRegister(0, ByteX2("C702"))))
      meas.size should equal(6)
      val resultSet = meas.map { case (name, m) => (name, m.getIntVal) }.toSet
      resultSet should equal(
        Set(("meas01", 6),
          ("meas02", 0),
          ("meas03", 0),
          ("meas04", 3),
          ("meas05", 1),
          ("meas06", 2)))
    }
  }

}