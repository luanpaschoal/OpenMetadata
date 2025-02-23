/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { WebhookType } from '../../generated/api/events/createWebhook';
import { Status, Webhook } from '../../generated/entity/events/webhook';
import { Paging } from '../../generated/type/paging';

export interface WebhooksProps {
  data: Array<Webhook>;
  paging: Paging;
  webhookType?: WebhookType;
  selectedStatus: Status[];
  currentPage: number;
  onAddWebhook: () => void;
  onClickWebhook: (name: string) => void;
  onPageChange: (type: string | number, activePage?: number) => void;
  onStatusFilter: (status: Status[]) => void;
}
