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
import io.greenbus.app.actor.frontend.ProtocolCommandAcceptor
import io.greenbus.client.service.proto.Commands.{ CommandRequest, CommandResult, CommandStatus }
import io.greenbus.modbus.xml.{ CommandMap, CommandType }
import org.totalgrid.modbus.ModbusOperations

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class CommandHandlerAdapter(mappings: Seq[CommandMap.Mapping], ops: ModbusOperations) extends ProtocolCommandAcceptor with LazyLogging {

  def errorStatus(status: CommandStatus, message: String) = Future.successful(CommandResult.newBuilder().setStatus(status).setErrorMessage(message).build())
  def badRequest(message: String) = errorStatus(CommandStatus.NOT_SUPPORTED, message)

  def mapFuture(fut: Future[Boolean]): Future[CommandResult] = {
    fut.map {
      case true => CommandResult.newBuilder().setStatus(CommandStatus.SUCCESS).build()
      case false => CommandResult.newBuilder().setStatus(CommandStatus.UNDEFINED).setErrorMessage("Modbus stack could not complete request").build()
    }
  }

  private val nameMap = {
    mappings.map(m => (m.getName, m)).toMap
  }

  private def simpleRegisterWrite(index: Int, value: Long): Future[CommandResult] = {
    if ((value >= 0 && value < 65536) || (value < 0 && value >= Short.MinValue)) {
      mapFuture(ops.writeSingleRegister(index, value.toInt))
    } else {
      badRequest("Setpoint value out of range for signed or unsigned 16-bit integer")
    }
  }

  private def multiRegisterWrite(index: Int, count: Int, value: Long): Future[CommandResult] = {
    val values: Seq[Int] = Range(0, count).map { i =>
      ((value >> (i * 16)) & 0xFFFF).toInt
    }

    mapFuture(ops.writeMultipleRegisters(index, values))
  }

  private def maskedRegisterWrite(index: Int, value: Long, maskStr: String, leftShift: Option[Int]): Future[CommandResult] = {
    val mask = MeasMap.parseBitMask(maskStr)

    ops.readHoldingRegisters(index, 1).flatMap {
      case Seq(orig) => {
        val origValue = orig.value.uInt16

        val shiftedInputValue: Int = leftShift.map(l => value.toInt << l).getOrElse(value.toInt)

        val targetValue = (origValue & ~mask) | (shiftedInputValue & mask)

        mapFuture(ops.writeSingleRegister(index, targetValue.toInt))
      }
      case _ => errorStatus(CommandStatus.UNDEFINED, "Did not recognize current state of holding register")
    }
  }

  def issue(name: String, request: CommandRequest): Future[CommandResult] = {

    def intValueOpt(mapping: CommandMap.Mapping): Option[Long] = {
      Option(mapping.getConstIntValue) match {
        case Some(constIntVal) => Some(constIntVal.toLong)
        case None =>
          if (request.hasType && request.getType == CommandRequest.ValType.INT) {
            Some(request.getIntVal)
          } else if (request.hasType && request.getType == CommandRequest.ValType.DOUBLE) {
            Some(request.getDoubleVal.toLong)
          } else {
            None
          }
      }
    }

    nameMap.get(name) match {
      case None => badRequest("No mapping for command")
      case Some(mapping) =>
        mapping.getCommandType match {
          case CommandType.COIL => {
            Option(mapping.getConstBoolValue) match {
              case None => badRequest("No constant coil value provided to translate control to")
              case Some(v) => mapFuture(ops.writeSingleCoil(mapping.getIndex, v))
            }
          }
          case CommandType.REGISTER => {
            val integerValueOpt: Option[Long] = intValueOpt(mapping)

            integerValueOpt match {
              case None => badRequest("Setpoint value not present or invalid and no constant value provided")
              case Some(value) => {
                Option(mapping.getBitMaskToUpdate) match {
                  case None => simpleRegisterWrite(mapping.getIndex, value)
                  case Some(maskStr) => maskedRegisterWrite(mapping.getIndex, value, maskStr, Option(mapping.getShiftLeft))
                }
              }
            }
          }
          case CommandType.MULTIPLE_REGISTERS => {
            val integerValueOpt: Option[Long] = intValueOpt(mapping)

            integerValueOpt match {
              case None => badRequest("Setpoint value not present or invalid and no constant value provided")
              case Some(value) => {
                val regCount = Option(mapping.getRegisterCount).map(_.toInt).getOrElse(1)
                if (regCount > 4 || regCount < 1) {
                  badRequest("Bad configuration, write multiple registers must have count 1-4")
                }
                Option(mapping.getBitMaskToUpdate) match {
                  case None => multiRegisterWrite(mapping.getIndex, regCount, value)
                  case Some(maskStr) =>
                    badRequest("Masked writes of multiple registers not supported")
                }
              }
            }
          }
          case CommandType.MASK_REGISTER => {
            val integerValueOpt: Option[Long] = intValueOpt(mapping)

            integerValueOpt match {
              case None => badRequest("Setpoint value not present or invalid and no constant value provided")
              case Some(value) => {
                val maskToUpdate: Long = Option(mapping.getBitMaskToUpdate) match {
                  case None => 0xFFFF
                  case Some(maskStr) => MeasMap.parseBitMask(maskStr)
                }

                val shiftLeftAmount = Option(mapping.getShiftLeft)

                val orMask = shiftLeftAmount.map(i => value << i).getOrElse(value)
                val andMask = (~maskToUpdate & 0xFF) | (~maskToUpdate & 0xFF00) // for the Modbus mask write function, mask is what of the current value to *preserve*

                mapFuture(ops.maskWriteRegister(mapping.getIndex, andMask.toInt, orMask.toInt))
              }
            }
          }
        }
    }
  }
}
