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
package io.greenbus.modbus.integration

import net.wimpi.modbus.net.ModbusTCPListener
import net.wimpi.modbus.ModbusCoupler
import net.wimpi.modbus.procimg._
import java.net.InetAddress

class ModbusSlave(port: Int) {

  def setDiscreteInput(index: Int, v: Boolean) {
    spi.setDigitalIn(index, new SimpleDigitalIn(v))
  }

  def setInputRegister(index: Int, v: Int) {
    spi.setInputRegister(index, new SimpleInputRegister(v))
  }

  def setHoldingRegister(index: Int, v: Int): Unit = {
    spi.setRegister(index, new SimpleRegister(v))
  }

  def getDigitalOut(index: Int): Boolean = {
    spi.getDigitalOut(index).isSet
  }

  def getHoldingRegister(index: Int): Int = {
    spi.getRegister(index).getValue
  }
  def getHoldingRegisterSigned(index: Int): Short = {
    spi.getRegister(index).toShort
  }

  private val listener = new ModbusTCPListener(3)

  private val spi = new SimpleProcessImage
  spi.addDigitalIn(new SimpleDigitalIn(false))
  spi.addDigitalIn(new SimpleDigitalIn(false))
  spi.addInputRegister(new SimpleInputRegister(0))
  spi.addInputRegister(new SimpleInputRegister(0))

  spi.addDigitalOut(new SimpleDigitalOut(false))
  spi.addDigitalOut(new SimpleDigitalOut(false))

  spi.addRegister(new SimpleInputRegister(0))
  spi.addRegister(new SimpleInputRegister(0))
  spi.addRegister(new SimpleInputRegister(0))
  spi.addRegister(new SimpleInputRegister(0))
  spi.addRegister(new SimpleInputRegister(0))
  spi.addRegister(new SimpleInputRegister(0))
  spi.addRegister(new SimpleInputRegister(0))
  spi.addRegister(new SimpleInputRegister(0))

  ModbusCoupler.getReference.setProcessImage(spi)
  ModbusCoupler.getReference.setMaster(false)
  ModbusCoupler.getReference.setUnitID(33)

  listener.setAddress(InetAddress.getLoopbackAddress)
  listener.setPort(port)
  listener.start()

  def close() {
    listener.stop()
  }
}
