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
import org.totalgrid.modbus.{ ModbusBit, ModbusDeviceObserver, ModbusRegister }
import io.greenbus.client.service.proto.Measurements
import io.greenbus.app.actor.frontend.{ MeasurementsPublished, StackStatusUpdated }
import io.greenbus.modbus.xml.DataType

class DeviceObserverAdapter(map: MeasMap, measPublish: MeasurementsPublished => Unit) extends ModbusDeviceObserver with LazyLogging {

  private def publish(pollType: DataType)(filter: => Traversable[(String, Measurements.Measurement)]) = {
    val out = filter.toList
    if (!out.isEmpty) {

      measPublish(MeasurementsPublished(System.currentTimeMillis, Seq(), out))
    } else {
      logger.warn("No measurements could be extracted from poll: " + pollType.value())
    }
  }

  override def onReadDiscreteInput(list: Traversable[ModbusBit]): Unit = publish(DataType.DISCRETE_INPUT)(map.convertDiscreteInput(list))

  override def onReadCoilStatus(list: Traversable[ModbusBit]): Unit = publish(DataType.COIL_STATUS)(map.convertCoilStatus(list))

  override def onReadInputRegister(list: Traversable[ModbusRegister]): Unit = publish(DataType.INPUT_REGISTER)(map.convertInputRegister(list))

  override def onReadHoldingRegister(list: Traversable[ModbusRegister]): Unit = publish(DataType.HOLDING_REGISTER)(map.convertHoldingRegister(list))

  override def onCommSuccess() = {}
  override def onCommFailure() = {}
}