///
/// Copyright © 2016-2024 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { ChangeDetectionStrategy, Component, forwardRef, Inject, OnDestroy } from '@angular/core';
import {
  FormBuilder,
  NG_VALIDATORS,
  NG_VALUE_ACCESSOR,
  UntypedFormControl,
  UntypedFormGroup,
  Validators,
} from '@angular/forms';
import {
  MappingInfo,
  ModbusMethodLabelsMap,
  ModbusMethodType,
  ModbusOrderType,
  ModbusProtocolLabelsMap,
  ModbusProtocolType,
  noLeadTrailSpacesRegex,
  PortLimits, SlaveConfig,
} from '@home/components/widget/lib/gateway/gateway-widget.models';
import { SharedModule } from '@shared/shared.module';
import { CommonModule } from '@angular/common';
import { Subject } from 'rxjs';
import {
  ModbusValuesComponent,
  ModbusSecurityConfigComponent,
} from '@home/components/widget/lib/gateway/connectors-configuration/modbus';
import { DialogComponent } from '@shared/components/dialog.component';
import { Store } from '@ngrx/store';
import { AppState } from '@core/core.state';
import { Router } from '@angular/router';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { GatewayPortTooltipPipe } from '@home/pipes/gateway-port-tooltip/gateway-port-tooltip.pipe';
import { takeUntil } from 'rxjs/operators';

@Component({
  selector: 'tb-modbus-slave-dialog',
  templateUrl: './modbus-slave-dialog.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => ModbusSlaveDialogComponent),
      multi: true
    },
    {
      provide: NG_VALIDATORS,
      useExisting: forwardRef(() => ModbusSlaveDialogComponent),
      multi: true
    }
  ],
  standalone: true,
  imports: [
    CommonModule,
    SharedModule,
    ModbusValuesComponent,
    ModbusSecurityConfigComponent,
    GatewayPortTooltipPipe,
  ],
  styles: [`
    :host {
      .slaves-config-container {
        width: 900px;
      }
      .nested-expansion-header {
        .mat-content {
          height: 100%;
          overflow: hidden;
        }
      }
    }
  `],
})
export class ModbusSlaveDialogComponent extends DialogComponent<ModbusSlaveDialogComponent, SlaveConfig> implements OnDestroy {

  slaveConfigFormGroup: UntypedFormGroup;
  showSecurityControl: UntypedFormControl;
  portLimits = PortLimits;

  readonly modbusProtocolTypes = Object.values(ModbusProtocolType);
  readonly modbusMethodTypes = Object.values(ModbusMethodType);
  readonly modbusOrderType = Object.values(ModbusOrderType);
  readonly ModbusProtocolType = ModbusProtocolType;
  readonly ModbusProtocolLabelsMap = ModbusProtocolLabelsMap;
  readonly ModbusMethodLabelsMap = ModbusMethodLabelsMap;
  readonly modbusHelpLink =
    'https://thingsboard.io/docs/iot-gateway/config/modbus/#section-master-description-and-configuration-parameters';
  readonly serialSpecificControlKeys = ['serialPort', 'baudrate', 'stopbits', 'bytesize', 'parity', 'strict'];
  readonly tcpUdpSpecificControlKeys = ['port', 'security', 'host'];

  private destroy$ = new Subject<void>();

  constructor(
    private fb: FormBuilder,
    protected store: Store<AppState>,
    protected router: Router,
    @Inject(MAT_DIALOG_DATA) public data: MappingInfo,
    public dialogRef: MatDialogRef<ModbusSlaveDialogComponent, SlaveConfig>,
  ) {
    super(store, router, dialogRef);

    this.showSecurityControl = this.fb.control(false);
    this.slaveConfigFormGroup = this.fb.group({
      name: ['', [Validators.required]],
      type: [ModbusProtocolType.TCP, [Validators.required]],
      host: ['', [Validators.required, Validators.pattern(noLeadTrailSpacesRegex)]],
      port: [null, [Validators.required, Validators.min(PortLimits.MIN), Validators.max(PortLimits.MAX)]],
      serialPort: ['', [Validators.pattern(noLeadTrailSpacesRegex)]],
      method: [ModbusMethodType.SOCKET, []],
      baudrate: [null, []],
      stopbits: [null, []],
      bytesize: [null, []],
      parity: [null, []],
      strict: [false, []],
      unitId: [null, []],
      deviceName: ['', [Validators.pattern(noLeadTrailSpacesRegex)]],
      deviceType: ['', [Validators.pattern(noLeadTrailSpacesRegex)]],
      sendDataOnlyOnChange: [false, []],
      timeout: [],
      byteOrder: [ModbusOrderType.BIG, []],
      wordOrder: [ModbusOrderType.BIG, []],
      retries: [false, []],
      retryOnEmpty: [false, []],
      retryOnInvalid: [false, []],
      pollPeriod: [null, []],
      connectAttemptTimeMs: [null, []],
      connectAttemptCount: [null, []],
      waitAfterFailedAttemptsMs: [null, []],
      values: [{}, []],
      security: [],
    });

    this.slaveConfigFormGroup.patchValue({
      ...this.data.value,
      values: {
        attributes: this.data.value.attributes ?? [],
        timeseries: this.data.value.timeseries ?? [],
        attributeUpdates: this.data.value.attributeUpdates ?? [],
        rpc: this.data.value.rpc ?? [],
      }
    });
    this.showSecurityControl.patchValue(!!this.data.value.security);
    this.updateControlsEnabling(this.data.value.type);
    this.observeTypeChange();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  add(): void {
    if (this.slaveConfigFormGroup.valid) {
      const slaveResult = {...this.slaveConfigFormGroup.value, ...this.slaveConfigFormGroup.value.values};
      delete slaveResult.values;
      if (slaveResult.type === ModbusProtocolType.Serial) {
        slaveResult.port = slaveResult.serialPort;
        delete slaveResult.serialPort;
      }
      this.dialogRef.close(slaveResult);
    }
  }

  private observeTypeChange(): void {
    this.slaveConfigFormGroup.get('type').valueChanges.pipe(takeUntil(this.destroy$)).subscribe(type => {
      this.updateControlsEnabling(type);
    });
  }

  private updateControlsEnabling(type: ModbusProtocolType): void {
    if (type === ModbusProtocolType.Serial) {
      this.serialSpecificControlKeys.forEach(key => this.slaveConfigFormGroup.get(key)?.enable({emitEvent: false}));
      this.tcpUdpSpecificControlKeys.forEach(key => this.slaveConfigFormGroup.get(key)?.disable({emitEvent: false}));
    } else {
      this.serialSpecificControlKeys.forEach(key => this.slaveConfigFormGroup.get(key)?.disable({emitEvent: false}));
      this.tcpUdpSpecificControlKeys.forEach(key => this.slaveConfigFormGroup.get(key)?.enable({emitEvent: false}));
    }
  };
}
