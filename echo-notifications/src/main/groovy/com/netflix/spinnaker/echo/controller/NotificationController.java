/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.echo.controller;

import com.netflix.spinnaker.echo.api.Notification;
import com.netflix.spinnaker.echo.api.events.NotificationAgent;
import com.netflix.spinnaker.echo.notification.InteractiveNotificationCallbackHandler;
import com.netflix.spinnaker.echo.notification.InteractiveNotificationService;
import com.netflix.spinnaker.echo.notification.NotificationService;
import groovy.util.logging.Slf4j;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/notifications")
@RestController
@Slf4j
@AllArgsConstructor
public class NotificationController {
  @Autowired(required = false)
  Collection<NotificationService> notificationServices;

  @Autowired private InteractiveNotificationCallbackHandler interactiveNotificationCallbackHandler;

  @Autowired Optional<List<NotificationAgent>> notificationAgents;

  /**
   * Provides an endpoint for other Spinnaker services to send out notifications to users.
   * Processing of the request is delegated to an implementation of {@link NotificationService}
   * appropriate for the {@link Notification#notificationType}.
   *
   * @param notification The notification to be sent, in an echo-generic format.
   * @return
   */
  @RequestMapping(method = RequestMethod.POST)
  public EchoResponse create(@RequestBody Notification notification) {

    notificationServices.stream()
        .forEach(
            it -> {
              if (!("SLACK".equals(notification.getNotificationType()))) {
                notification.setUseInteractiveBot(false);
                notification.setInteractiveActions(null);
              }
            });
    val notificationService =
        notificationServices.stream()
            .filter(
                it ->
                    it.supportsType(notification.getNotificationType())
                        &&
                        // Only delegate interactive notifications to interactive notification
                        // services, and vice-versa.
                        // This allows us to support two implementations in parallel for the same
                        // notification type.
                        (it instanceof InteractiveNotificationService
                            ? notification.isInteractive()
                            : !notification.isInteractive()))
            .findFirst();
    return notificationService.map(ns -> ns.handle(notification)).orElse(null);
  }

  /**
   * Provides a generic callback API for notification services to call, primarily in response to
   * interactive user action (e.g. clicking a button in a message). This method makes as few
   * assumptions as possible about the request, delegating the raw request headers, parameters and
   * body to the corresponding [InteractiveNotificationService] to process, and similarly allows the
   * notification service to return an arbitrary response body to the caller.
   *
   * <p>Note that this method must be exposed externally through gate so that external services
   * (e.g. Slack) may call back to echo.
   *
   * @param source The unique ID of the calling notification service (e.g. "slack")
   * @param headers The request headers
   * @param rawBody The raw body of the request
   * @param parameters The request parameters, parsed as a Map
   * @return
   */
  @RequestMapping(
      value = "/callbacks/{source}",
      method = RequestMethod.POST,
      consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> processCallback(
      @PathVariable String source, RequestEntity<String> request) {
    return interactiveNotificationCallbackHandler.processCallback(source, request);
  }

  @GetMapping("/metadata")
  public List<NotificationAgent> getNotificationTypeMetadata() {
    return notificationAgents.orElseGet(ArrayList::new);
  }
}
