package com.waygo.backend.service;

import com.waygo.backend.entity.DriverOffer;
import com.waygo.backend.entity.MapSettings;
import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.RideBooking;
import com.waygo.backend.entity.User;
import com.waygo.backend.entity.VipChatMessage;
import com.waygo.backend.entity.PassengerChatMessage;
import com.waygo.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final SmsService smsService;
    private final SmsService realSmsService;
    private final UserRepository userRepository;

    public NotificationService(
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("dynamicSmsService") SmsService realSmsService,
            UserRepository userRepository
    ) {
        this.messagingTemplate = messagingTemplate;
        this.smsService = realSmsService;
        this.realSmsService = realSmsService;
        this.userRepository = userRepository;
    }

    public void notifyNewOrder(Order order) {
        // Only a passenger's ride request should notify anyone on creation — it's
        // relevant to drivers deciding whether to bid. A driver's own announcement
        // (passenger == null) must stay silent for passengers here; drivers browse
        // the announcement list on demand instead of being pushed a notification.
        if (order.getPassenger() != null) {
            messagingTemplate.convertAndSend("/topic/orders/new-for-drivers", order);
            broadcastNewOrderPush(User.Role.DRIVER, order, "Yangi buyurtma! 🚕");
        }
    }

    /**
     * The STOMP broadcast above only reaches a client with a live socket
     * connection, which drops the moment the app process is killed (not
     * just backgrounded) — confirmed on a real device as "new order never
     * arrives while the driver app is fully closed, but works fine while
     * it's open". An FCM push is delivered by the OS itself even when the
     * app isn't running at all, so every recipient with a stored token gets
     * one alongside the socket broadcast, exactly mirroring who the socket
     * topic above already targets (every driver for a passenger's request,
     * every passenger for a driver's announcement — this app has no
     * "online now" roster to narrow that down to, same as the STOMP topic).
     */
    private void broadcastNewOrderPush(User.Role role, Order order, String title) {
        String fromLoc = order.getFromAddress() != null ? order.getFromAddress() : "";
        String toLoc = order.getToAddress() != null ? order.getToAddress() : "";
        String body = "Qatnov: " + fromLoc + " -> " + toLoc;

        java.util.Map<String, String> extraData = new java.util.HashMap<>();
        extraData.put("orderId", String.valueOf(order.getId()));

        for (User recipient : userRepository.findByRoleOrderByCreatedAtDesc(role)) {
            sendFcmNotification(recipient, title, body, "NEW_ORDER", extraData);
        }
    }

    /**
     * Re-broadcasts a still-PENDING (unassigned) passenger order on the same
     * public channel {@link #notifyNewOrder} uses, for a field edit (e.g. the
     * pickup point) rather than the order's initial creation. A driver who's
     * already viewing this order's detail page before making any offer isn't
     * "the assigned driver" yet — {@code order.getDriver()} is still null —
     * so there's no single recipient to target a personal push at; every
     * browsing driver's client filters by matching order id anyway, the same
     * way it already does for the initial new-order broadcast.
     *
     * Also sends the same FCM push every driver gets for the order's initial
     * creation (see {@link #notifyNewOrder}) — without it, an edit to a
     * request no driver has offered on yet reached no one whose app wasn't
     * open and connected to the WebSocket at that exact moment.
     */
    public void notifyPendingOrderUpdated(Order order) {
        if (order == null || order.getPassenger() == null || order.getDriver() != null) {
            return;
        }
        messagingTemplate.convertAndSend("/topic/orders/new-for-drivers", order);
        broadcastNewOrderPush(User.Role.DRIVER, order, "Buyurtma yangilandi! 🚕");
    }

    public void notifyOrderStatusUpdate(Order order) {
        notifyOrderStatusUpdate(order, true);
    }

    /**
     * @param notifyAssignedDriver Pass false when the assigned driver has already
     *   received (or will receive) an equivalent push for this same trip through a
     *   different Order row — e.g. confirmDriverOffer() merges the passenger's
     *   request into the driver's own announcement Order and pushes that instead.
     *   Sending both here would put two cards for one trip in the driver's app.
     */
    public void notifyOrderStatusUpdate(Order order, boolean notifyAssignedDriver) {
        notifyOrderStatusUpdate(order, notifyAssignedDriver, true);
    }

    /**
     * @param sendFcmPush Pass false when the caller only needs the live WebSocket
     *   order refresh (passenger/driver screens re-rendering with the latest
     *   Order), not an actual push — e.g. lockOrder()/unlockOrder() re-save the
     *   order to record a driver's 30s soft-reservation, which never changes
     *   order.getStatus(). Those used to go through the FCM branch below too,
     *   which sent a "Buyurtma holati yangilandi: PENDING" push describing a
     *   status change that never happened — confirmed on a real device as a
     *   burst of near-identical PENDING pushes whenever several drivers
     *   locked/viewed the same fresh request within a couple of minutes.
     */
    public void notifyOrderStatusUpdate(Order order, boolean notifyAssignedDriver, boolean sendFcmPush) {
        String msg = "WayGO: Buyurtmangiz holati yangilandi: " + order.getStatus();

        // Driver's own ride announcement (no passenger attached to the Order
        // itself) has no single recipient to target — every passenger browsing
        // the "Haydovchilar" tab needs to see this live, the same way drivers
        // already get passenger requests pushed via /topic/orders/new-for-drivers.
        // WS-only (no FCM): this fires on every status change of the announcement
        // (new bookings, seat updates, cancellation), and a push notification per
        // change to every passenger would be spam — the point here is just to
        // keep an already-open browsing list in sync, not to alert anyone.
        if (order.getPassenger() == null && order.getDriver() != null) {
            messagingTemplate.convertAndSend("/topic/orders/new-for-passengers", order);
        }

        // Notify the specific passenger about their order status update if present
        if (order.getPassenger() != null) {
            messagingTemplate.convertAndSendToUser(
                    order.getPassenger().getPhone(),
                    "/queue/order-status",
                    order
            );

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "ORDER_UPDATE");
            payload.put("order", order);
            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + order.getPassenger().getId(),
                    payload
            );

            if (sendFcmPush) {
                if (order.getStatus() == Order.OrderStatus.ARRIVED) {
                    sendFcmNotification(
                            order.getPassenger(),
                            "Haydovchi yetib keldi! 📍",
                            "Haydovchi belgilangan jo'nash joyiga yetib keldi.",
                            "ORDER_UPDATE",
                            null,
                            "driver_arrived_chime"
                    );
                } else if (order.getStatus() == Order.OrderStatus.COMPLETED) {
                    // Distinct TRIP_COMPLETED push (not the generic ORDER_UPDATE
                    // one below) so the client can route a tap straight to the
                    // rating screen instead of the live-tracking map — mirrors
                    // notifyTripCompleted()'s push to route-booking passengers,
                    // which this direct/primary passenger was missing out on.
                    java.util.Map<String, String> extraData = new java.util.HashMap<>();
                    extraData.put("orderId", String.valueOf(order.getId()));
                    sendFcmNotification(
                            order.getPassenger(),
                            "Safar yakunlandi! 🏁",
                            "Safar yakunlandi! Haydovchini baholashni unutmang.",
                            "TRIP_COMPLETED",
                            extraData
                    );
                } else {
                    sendFcmNotification(order.getPassenger(), "Buyurtma holati yangilandi", msg, "ORDER_UPDATE");
                }
            }
        }

        // Notify passengers attached to announcement bookings — only the ones
        // still actually on this route (ACCEPTED/COLLECTED). A RideBooking is
        // never removed from order.getBookings() when rejected/cancelled
        // (only its status flag changes, see rejectBooking()), so without this
        // filter a passenger whose request was long rejected/cancelled still
        // received every later ORDER_UPDATE for this order — including a
        // STARTED push that made waygo_user auto-open the live-tracking map
        // for a trip they have nothing to do with (see notifyTripStarted,
        // which already applies this same filter).
        if (order.getBookings() != null) {
            for (RideBooking b : order.getBookings()) {
                if (b == null || b.getPassenger() == null) {
                    continue;
                }
                String bStatus = b.getStatus();
                boolean isActivePassenger = "ACCEPTED".equalsIgnoreCase(bStatus) || "COLLECTED".equalsIgnoreCase(bStatus);
                if (!isActivePassenger) {
                    continue;
                }
                java.util.Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("type", "ORDER_UPDATE");
                payload.put("order", order);
                messagingTemplate.convertAndSend(
                        "/topic/notifications/" + b.getPassenger().getId(),
                        payload
                );
            }
        }

        // Also notify the directly assigned driver if present
        if (notifyAssignedDriver && order.getDriver() != null) {
            messagingTemplate.convertAndSendToUser(
                    order.getDriver().getPhone(),
                    "/queue/order-status",
                    order
            );

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "ORDER_UPDATE");
            payload.put("order", order);
            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + order.getDriver().getId(),
                    payload
            );

            if (sendFcmPush) {
                sendFcmNotification(order.getDriver(), "Buyurtma holati yangilandi", msg, "ORDER_UPDATE");
            }
        }

        // Notify ALL drivers who submitted offers so they learn if accepted/rejected
        if (order.getDriverOffers() != null) {
            for (DriverOffer offer : order.getDriverOffers()) {
                if (offer.getDriver() != null) {
                    // Avoid double-notifying the already assigned driver (already notified above)
                    boolean isAssignedDriver = order.getDriver() != null &&
                            order.getDriver().getId().equals(offer.getDriver().getId());
                    if (!isAssignedDriver) {
                        messagingTemplate.convertAndSendToUser(
                                offer.getDriver().getPhone(),
                                "/queue/order-status",
                                order
                        );
                    }
                }
            }
        }

    }

    /**
     * Notifies the passenger that a driver placed a NEW offer on their
     * still-PENDING request — called from {@code acceptOrder()}, where a
     * {@link DriverOffer} is added/updated but {@code order.getStatus()} itself
     * never changes. That call site used to go through
     * {@link #notifyOrderStatusUpdate(Order)}, which pushed "Buyurtma holati
     * yangilandi: PENDING" — misleading (status didn't change) and, with several
     * drivers bidding on the same fresh request within a couple of minutes,
     * produced a burst of near-identical "PENDING" pushes with nothing new to
     * report. This sends the same live WebSocket order refresh (so the
     * passenger's offer list updates immediately) but with FCM copy that
     * actually describes what happened — including the specific price THIS
     * driver offered ({@code offeredPrice}, the just-saved DriverOffer's own
     * pricePerPerson), never {@code order.getPrice()} — with several drivers
     * able to bid different prices on the same request, showing the order's
     * own price here would silently show every passenger the same number
     * regardless of which driver's offer the push was actually about.
     */
    public void notifyNewDriverOffer(Order order, java.math.BigDecimal offeredPrice) {
        if (order.getPassenger() == null) {
            return;
        }

        messagingTemplate.convertAndSendToUser(
                order.getPassenger().getPhone(),
                "/queue/order-status",
                order
        );

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "ORDER_UPDATE");
        payload.put("order", order);
        messagingTemplate.convertAndSend(
                "/topic/notifications/" + order.getPassenger().getId(),
                payload
        );

        String body;
        if (offeredPrice != null) {
            java.text.NumberFormat nf = java.text.NumberFormat.getInstance(new java.util.Locale("uz", "UZ"));
            nf.setGroupingUsed(true);
            nf.setMaximumFractionDigits(0);
            String formattedPrice = nf.format(offeredPrice.setScale(0, java.math.RoundingMode.HALF_UP));
            body = "WayGO: Haydovchi sizga " + formattedPrice + " so'm narx taklif qildi.";
        } else {
            body = "WayGO: Haydovchi sizning so'rovingizga yangi taklif yubordi.";
        }

        sendFcmNotification(
                order.getPassenger(),
                "Yangi taklif! 🚗",
                body,
                "ORDER_UPDATE"
        );
    }

    public void notifySeatCancelled(User passenger, String seatName, Order order) {
        if (passenger != null && passenger.getPhone() != null) {
            String msg = "WayGO: Haydovchi sizning \"" + seatName + "\" o'rindig'ingizni bekor qildi.";

            // Send private WebSocket update to passenger so they immediately receive it
            messagingTemplate.convertAndSendToUser(
                    passenger.getPhone(),
                    "/queue/order-status",
                    order
            );
            
            sendFcmNotification(passenger, "Joy bekor qilindi", msg, "ORDER_UPDATE");
        }
    }

    public void notifyBookingConfirmed(RideBooking booking) {
        if (booking == null || booking.getPassenger() == null) {
            return;
        }

        String phone = booking.getPassenger().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        Order order = booking.getOrder();
        String fromLoc = order != null ? order.getFromAddress() : "";
        String toLoc = order != null ? order.getToAddress() : "";

        String msg = "WayGO: Haydovchi sizning so'rovingizni tasdiqladi! Qatnov: " + fromLoc + " -> " + toLoc;

        if (order != null) {
            messagingTemplate.convertAndSendToUser(
                    phone,
                    "/queue/order-status",
                    order
            );
            sendFcmNotification(booking.getPassenger(), "So'rov tasdiqlandi", msg, "ORDER_UPDATE");
        }
    }

    public void notifyBookingRejected(RideBooking booking) {
        if (booking == null || booking.getPassenger() == null) {
            return;
        }

        String phone = booking.getPassenger().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        Order order = booking.getOrder();
        String fromLoc = order != null ? order.getFromAddress() : "";
        String toLoc = order != null ? order.getToAddress() : "";

        String msg = "WayGO: Afsuski, haydovchi sizning so'rovingizni rad etdi. Qatnov: " + fromLoc + " -> " + toLoc;

        if (order != null) {
            messagingTemplate.convertAndSendToUser(
                    phone,
                    "/queue/order-status",
                    order
            );
            sendFcmNotification(booking.getPassenger(), "So'rov rad etildi", msg, "ORDER_UPDATE");
        }
    }

    public void notifyPassengerOrderCancelledByDriver(Order passengerOrder, Order driverOrder) {
        if (passengerOrder == null || passengerOrder.getPassenger() == null) {
            return;
        }
        String phone = passengerOrder.getPassenger().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        String msg = "WayGO: Afsuski, haydovchi o'z qatnovini bekor qildi. Shu sababli sizning buyurtmangiz bekor qilindi.";

        messagingTemplate.convertAndSendToUser(
                phone,
                "/queue/order-status",
                passengerOrder
        );

        // Also broadcast on the numeric-ID topic — see notifyOrderStatusUpdate's
        // use of the same pair. The STOMP-user /queue channel above depends on
        // the phone-based principal resolving correctly on delivery; the
        // waygo_user client's own web_socket_service.dart subscribes to this
        // topic too, explicitly "for absolute reliability". This method used to
        // send only the /queue message, which is why a driver's full order
        // cancellation was confirmed to sometimes never reach the passenger —
        // their screen kept showing the now-cancelled order until their next
        // manual refresh.
        java.util.Map<String, Object> passengerCancelPayload = new java.util.HashMap<>();
        passengerCancelPayload.put("type", "ORDER_UPDATE");
        passengerCancelPayload.put("order", passengerOrder);
        messagingTemplate.convertAndSend(
                "/topic/notifications/" + passengerOrder.getPassenger().getId(),
                passengerCancelPayload
        );

        sendFcmNotification(passengerOrder.getPassenger(), "Buyurtma bekor qilindi", msg, "ORDER_UPDATE");
    }

    public void notifyDriverOrderCancelledByPassenger(Order passengerOrder) {
        if (passengerOrder == null || passengerOrder.getDriver() == null) {
            return;
        }
        String phone = passengerOrder.getDriver().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        String msg = "WayGO: Yo'lovchi o'z buyurtmasini bekor qildi. Qatnov: " +
                (passengerOrder.getFromAddress() != null ? passengerOrder.getFromAddress() : "") + " -> " +
                (passengerOrder.getToAddress() != null ? passengerOrder.getToAddress() : "");

        messagingTemplate.convertAndSendToUser(
                phone,
                "/queue/order-status",
                passengerOrder
        );

        // Same reliability pairing as notifyPassengerOrderCancelledByDriver
        // above — waygo_driver's web_socket_service.dart subscribes to this
        // numeric-ID topic alongside /queue/order-status for the same reason.
        java.util.Map<String, Object> driverCancelPayload = new java.util.HashMap<>();
        driverCancelPayload.put("type", "ORDER_UPDATE");
        driverCancelPayload.put("order", passengerOrder);
        messagingTemplate.convertAndSend(
                "/topic/notifications/" + passengerOrder.getDriver().getId(),
                driverCancelPayload
        );

        sendFcmNotification(passengerOrder.getDriver(), "Buyurtma bekor qilindi", msg, "ORDER_UPDATE");
    }

    public void notifyBookingCancelledByDriver(RideBooking booking, Order driverOrder) {
        if (booking == null || booking.getPassenger() == null) {
            return;
        }
        String phone = booking.getPassenger().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        String msg = "WayGO: Afsuski, haydovchi o'z qatnovini bekor qildi. Shu sababli sizning band qilgan o'rindig'ingiz bekor qilindi.";

        if (driverOrder != null) {
            messagingTemplate.convertAndSendToUser(
                    phone,
                    "/queue/order-status",
                    driverOrder
            );

            // Same reliability pairing as notifyPassengerOrderCancelledByDriver.
            java.util.Map<String, Object> bookingCancelPayload = new java.util.HashMap<>();
            bookingCancelPayload.put("type", "ORDER_UPDATE");
            bookingCancelPayload.put("order", driverOrder);
            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + booking.getPassenger().getId(),
                    bookingCancelPayload
            );

            sendFcmNotification(booking.getPassenger(), "Joy bekor qilindi", msg, "ORDER_UPDATE");
        }
    }
    
    public void notifySeatBookedByPassenger(Order driverOrder, User passenger) {
        if (driverOrder == null || driverOrder.getDriver() == null) {
            return;
        }
        String phone = driverOrder.getDriver().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        String msg = "WayGO: Yo'lovchi sizning qatnovingizda joy band qildi!";
        
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "ORDER_UPDATE");
        payload.put("order", driverOrder);
        messagingTemplate.convertAndSend(
                "/topic/notifications/" + driverOrder.getDriver().getId(),
                payload
        );
        
        sendFcmNotification(driverOrder.getDriver(), "Yangi yo'lovchi", msg, "ORDER_UPDATE");
    }

    public void notifyNextPassengerTurn(User nextPassenger, User driver, Long orderId) {
        notifyNextPassengerTurn(nextPassenger, driver, orderId, null, null);
    }

    public void notifyNextPassengerTurn(User nextPassenger, User driver, Long orderId, Double pickupLat, Double pickupLon) {
        if (nextPassenger == null) {
            return;
        }

        String driverName = (driver != null && driver.getFullName() != null && !driver.getFullName().isEmpty())
                ? driver.getFullName()
                : "Haydovchi";

        String msg = "Navbat sizga keldi! " + driverName + " sizni olib ketgani yo'lga chiqdi. Ilovada real vaqtda kuzatishingiz mumkin!";

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "NEXT_PASSENGER_TURN");
        payload.put("message", msg);

        if (orderId != null) {
            payload.put("orderId", orderId);
        }

        if (driver != null) {
            payload.put("driverId", driver.getId());
        }

        if (pickupLat != null && pickupLon != null && pickupLat != 0.0 && pickupLon != 0.0) {
            payload.put("pickupLat", pickupLat);
            payload.put("pickupLon", pickupLon);
        }

        messagingTemplate.convertAndSend(
                "/topic/notifications/" + nextPassenger.getId(),
                payload
        );

        if (nextPassenger.getPhone() != null) {
            messagingTemplate.convertAndSendToUser(
                    nextPassenger.getPhone(),
                    "/queue/notifications",
                    payload
            );
        }

        sendFcmNotification(nextPassenger, "Navbat sizga keldi! 🚖", msg, "NEXT_PASSENGER_TURN");
    }

    // Driver-triggered "I've arrived" for a single route/booking passenger —
    // the multi-passenger equivalent of the solo-order ARRIVED push above,
    // since a shared route Order has no single "arrived" status that would
    // make sense for every booking on it at once (see OrderService.notifyBookingArrived).
    public void notifyBookingArrived(User passenger, User driver, Long orderId) {
        if (passenger == null) {
            return;
        }

        String driverName = (driver != null && driver.getFullName() != null && !driver.getFullName().isEmpty())
                ? driver.getFullName()
                : "Haydovchi";

        String msg = driverName + " olib ketish joyingizga yetib keldi. Sizni kutmoqda!";

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "BOOKING_ARRIVED");
        payload.put("message", msg);
        if (orderId != null) {
            payload.put("orderId", orderId);
        }
        if (driver != null) {
            payload.put("driverId", driver.getId());
        }

        messagingTemplate.convertAndSend(
                "/topic/notifications/" + passenger.getId(),
                payload
        );

        if (passenger.getPhone() != null) {
            messagingTemplate.convertAndSendToUser(
                    passenger.getPhone(),
                    "/queue/notifications",
                    payload
            );
        }

        sendFcmNotification(passenger, "Haydovchi yetib keldi! 📍", msg, "BOOKING_ARRIVED", null, "driver_arrived_chime");
    }

    /**
     * Notifies every booked passenger on a driver's route announcement that the
     * trip has actually started (fired once, from the "SAFARNI BOSHLASH" action,
     * when all pickups are done and the driver begins driving to the final
     * destination). By that point every {@link RideBooking} on the order is
     * already COLLECTED, so {@link #notifyNextPassengerTurn} — which only ever
     * targets the first non-collected booking in sequence — finds no one left
     * to notify. This method closes that gap by pushing to the whole route,
     * not just the next passenger in a still-in-progress pickup sequence.
     */
    public void notifyTripStarted(Order order) {
        if (order == null || order.getBookings() == null) {
            return;
        }

        String fromLoc = order.getFromAddress() != null ? order.getFromAddress() : "";
        String toLoc = order.getToAddress() != null ? order.getToAddress() : "";
        String msg = "Haydovchi safarni boshladi! Qatnov: " + fromLoc + " -> " + toLoc
                + ". Ilovada real vaqtda kuzatishingiz mumkin.";

        for (RideBooking b : order.getBookings()) {
            if (b == null || b.getPassenger() == null) {
                continue;
            }
            String status = b.getStatus();
            boolean isActivePassenger = "ACCEPTED".equalsIgnoreCase(status) || "COLLECTED".equalsIgnoreCase(status);
            if (!isActivePassenger) {
                continue;
            }

            User passenger = b.getPassenger();

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "TRIP_STARTED");
            payload.put("message", msg);
            payload.put("orderId", order.getId());
            if (order.getDriver() != null) {
                payload.put("driverId", order.getDriver().getId());
            }

            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + passenger.getId(),
                    payload
            );

            if (passenger.getPhone() != null) {
                messagingTemplate.convertAndSendToUser(
                        passenger.getPhone(),
                        "/queue/notifications",
                        payload
                );
            }

            java.util.Map<String, String> extraData = new java.util.HashMap<>();
            extraData.put("orderId", String.valueOf(order.getId()));
            if (order.getDriver() != null) {
                extraData.put("driverId", String.valueOf(order.getDriver().getId()));
            }
            sendFcmNotification(passenger, "Safar boshlandi! 🚖", msg, "TRIP_STARTED", extraData);
        }
    }

    /**
     * Notifies every booked passenger on a driver's route announcement that
     * the trip has fully completed, mirroring {@link #notifyTripStarted}.
     * {@link #notifyOrderStatusUpdate} already fires for every status change
     * including COMPLETED, but — same gap found and fixed for STARTED — it
     * only sends an actual FCM push to the single primary passenger
     * (order.getPassenger()), not to route-booking passengers, who'd
     * otherwise only find out the trip ended if their app happens to be
     * open and connected to the WebSocket at that exact moment.
     */
    public void notifyTripCompleted(Order order) {
        if (order == null || order.getBookings() == null) {
            return;
        }

        String msg = "Safar yakunlandi! Haydovchini baholashni unutmang.";

        for (RideBooking b : order.getBookings()) {
            if (b == null || b.getPassenger() == null) {
                continue;
            }
            String status = b.getStatus();
            boolean isActivePassenger = "ACCEPTED".equalsIgnoreCase(status) || "COLLECTED".equalsIgnoreCase(status);
            if (!isActivePassenger) {
                continue;
            }

            User passenger = b.getPassenger();

            // No STOMP/WebSocket send here (unlike notifyOrderStatusUpdate) —
            // the waygo_user client never handled a TRIP_COMPLETED case in its
            // notification listener, so that message was received and
            // silently dropped every time. The passenger-facing completion
            // UX (live-tracking screen reaction + rating prompt) is already
            // fully driven by notifyOrderStatusUpdate's own ORDER_UPDATE
            // message. Only the FCM push below is real, unique work this
            // method does — see the class doc comment above for why it's
            // still needed per-route-passenger.
            java.util.Map<String, String> extraData = new java.util.HashMap<>();
            extraData.put("orderId", String.valueOf(order.getId()));
            sendFcmNotification(passenger, "Safar yakunlandi! 🏁", msg, "TRIP_COMPLETED", extraData);
        }
    }

    /**
     * Notifies the assigned driver that a passenger changed their precise
     * pickup point ("Olib ketish joyi") on an order the driver has already
     * accepted. A real, dedicated push (not just the silent WebSocket ping
     * that {@code notifyOrderStatusUpdate(order, true, false)} already sends
     * to move the driver's map) so the driver actually notices the pin
     * moved, instead of the generic/misleading "Buyurtma holati yangilandi"
     * text a plain status-update push would show for something that isn't
     * actually a status change.
     */
    public void notifyPickupLocationChanged(Order order) {
        if (order == null || order.getDriver() == null) {
            return;
        }
        java.util.Map<String, String> extraData = new java.util.HashMap<>();
        extraData.put("orderId", String.valueOf(order.getId()));
        sendFcmNotification(
                order.getDriver(),
                "Olib ketish joyi o'zgartirildi 📍",
                "Yo'lovchi olib ketish manzilini o'zgartirdi.",
                "PICKUP_LOCATION_UPDATED",
                extraData
        );
    }

    /**
     * Notifies every booked passenger on a driver's route announcement that
     * the driver un-started the trip (reverted STARTED back to ACCEPTED),
     * mirroring {@link #notifyTripStarted}. Passengers who already received
     * the "trip started" push may be acting on it (heading to a meeting
     * point, etc.), so they need an explicit heads-up that it isn't underway
     * yet after all.
     */
    public void notifyTripStartCancelled(Order order) {
        if (order == null || order.getBookings() == null) {
            return;
        }

        String msg = "Haydovchi safarni boshlashni bekor qildi. Safar hali boshlanmadi, iltimos kuting.";

        for (RideBooking b : order.getBookings()) {
            if (b == null || b.getPassenger() == null) {
                continue;
            }
            String status = b.getStatus();
            boolean isActivePassenger = "ACCEPTED".equalsIgnoreCase(status) || "COLLECTED".equalsIgnoreCase(status);
            if (!isActivePassenger) {
                continue;
            }

            User passenger = b.getPassenger();

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "TRIP_START_CANCELLED");
            payload.put("message", msg);
            payload.put("orderId", order.getId());
            if (order.getDriver() != null) {
                payload.put("driverId", order.getDriver().getId());
            }

            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + passenger.getId(),
                    payload
            );

            if (passenger.getPhone() != null) {
                messagingTemplate.convertAndSendToUser(
                        passenger.getPhone(),
                        "/queue/notifications",
                        payload
                );
            }

            java.util.Map<String, String> extraData = new java.util.HashMap<>();
            extraData.put("orderId", String.valueOf(order.getId()));
            if (order.getDriver() != null) {
                extraData.put("driverId", String.valueOf(order.getDriver().getId()));
            }
            sendFcmNotification(passenger, "Safar boshlanishi bekor qilindi", msg, "TRIP_START_CANCELLED", extraData);
        }
    }

    public void notifyBalanceUpdate(User user, java.math.BigDecimal amount) {
        if (user == null || user.getPhone() == null) {
            return;
        }
        java.text.NumberFormat nf = java.text.NumberFormat.getInstance(new java.util.Locale("uz", "UZ"));
        nf.setGroupingUsed(true);
        nf.setMaximumFractionDigits(0);

        String formattedAmount  = nf.format(amount.setScale(0, java.math.RoundingMode.HALF_UP));
        String formattedBalance = nf.format(
                (user.getBalance() != null ? user.getBalance() : java.math.BigDecimal.ZERO)
                        .setScale(0, java.math.RoundingMode.HALF_UP));

        String msg = "WayGoUz: Hisobingizga " + formattedAmount + " so'm tushdi. "
                   + "Joriy balansingiz: " + formattedBalance + " so'm.";

        // Send SMS via Eskiz
        smsService.sendSms(user.getPhone(), msg);

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "BALANCE_UPDATE");
        payload.put("amount", amount);
        payload.put("balance", user.getBalance());
        payload.put("message", msg);

        // Send directly to the user's numeric ID specific topic to avoid special character (+) routing issues in STOMP
        messagingTemplate.convertAndSend(
                "/topic/notifications/" + user.getId(),
                payload
        );
        
        sendFcmNotification(user, "Balans yangilandi", msg, "BALANCE_UPDATE");
    }

    // Lets an online driver's app refresh its own cached profile (via
    // AuthBloc's notificationStream listener) right after a passenger rates
    // them, mirroring notifyBalanceUpdate/notifyTariffUpdate — without this,
    // the new rating is correct in the DB/API but the app keeps showing the
    // stale value until the driver manually pulls-to-refresh or restarts.
    public void notifyRatingUpdate(User driver, Double newRating, Integer ratingCount) {
        if (driver == null || driver.getId() == null) {
            return;
        }
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "RATING_UPDATE");
        payload.put("rating", newRating);
        payload.put("ratingCount", ratingCount);

        messagingTemplate.convertAndSend(
                "/topic/notifications/" + driver.getId(),
                payload
        );
    }

    public void notifyTariffUpdate(User user, String message) {
        if (user == null || user.getPhone() == null) {
            return;
        }
        smsService.sendSms(user.getPhone(), "WayGO: " + message);

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "TARIFF_UPDATE");
        payload.put("message", message);

        // Send directly to the user's numeric ID specific topic
        messagingTemplate.convertAndSend(
                "/topic/notifications/" + user.getId(),
                payload
        );
        
        sendFcmNotification(user, "Tarif yangilandi", message, "TARIFF_UPDATE");
    }

    public void notifyNewChatMessage(VipChatMessage message) {
        if (message == null || message.getDriver() == null) {
            return;
        }
        
        Runnable sendNotification = () -> {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "CHAT_MESSAGE");
            payload.put("messageText", message.getMessageText());
            payload.put("sender", message.getSender().name());
            payload.put("createdAt", message.getCreatedAt() != null ? message.getCreatedAt().toString() : java.time.LocalDateTime.now().toString());
            payload.put("id", message.getId());

            // Send directly to the user's numeric ID specific topic
            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + message.getDriver().getId(),
                    payload
            );
            
            // Send Push Notification
            sendFcmNotification(message.getDriver(), "Yangi xabar", message.getMessageText(), "CHAT_MESSAGE");
        };

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sendNotification.run();
                    }
                }
            );
        } else {
            sendNotification.run();
        }
    }

    public void notifyNewPassengerChatMessage(PassengerChatMessage message) {
        if (message == null || message.getPassenger() == null) {
            return;
        }

        Runnable sendNotification = () -> {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "CHAT_MESSAGE");
            payload.put("messageText", message.getMessageText());
            payload.put("sender", message.getSender().name());
            payload.put("createdAt", message.getCreatedAt() != null ? message.getCreatedAt().toString() : java.time.LocalDateTime.now().toString());
            payload.put("id", message.getId());

            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + message.getPassenger().getId(),
                    payload
            );

            sendFcmNotification(message.getPassenger(), "Yangi xabar", message.getMessageText(), "CHAT_MESSAGE");
        };

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sendNotification.run();
                    }
                }
            );
        } else {
            sendNotification.run();
        }
    }

    /**
     * Admin panelda Xarita Sozlamalari saqlanganda barcha ulangan
     * waygo_user/waygo_driver mijozlariga yuboriladi — ular ilovani qayta
     * ochmasdan yangi qiymatlarni darhol qo'llash uchun. Boshqa notify*
     * metodlaridan farqli o'laroq "type" bilan o'ralmaydi: /topic/map-settings
     * yagona maqsadli topic, shuning uchun payload REST
     * /api/v1/config/map-settings bilan bir xil shaklda — klient tomonidagi
     * mavjud JSON parserni o'zgarishsiz qayta ishlatish uchun.
     */
    public void notifyMapSettingsUpdated(MapSettings settings) {
        messagingTemplate.convertAndSend("/topic/map-settings", settings);
    }

    private void sendFcmNotification(User user, String title, String body, String type) {
        sendFcmNotification(user, title, body, type, null, null);
    }

    private void sendFcmNotification(User user, String title, String body, String type, java.util.Map<String, String> extraData) {
        sendFcmNotification(user, title, body, type, extraData, null);
    }

    /**
     * @param soundName When non-null, plays a bundled custom notification sound
     *   instead of the default channel sound — works even while the app is
     *   backgrounded or fully killed, since the OS plays it natively rather than
     *   any Dart code running. Requires the client to have registered an Android
     *   notification channel named "{soundName}_channel" with that sound bundled
     *   as android/app/src/main/res/raw/{soundName}.mp3 (channel sound settings
     *   are immutable after first creation on-device — the channel ID must be
     *   unique per distinct sound), and the sound file bundled in the iOS app as
     *   "{soundName}.caf" for APNs to find by filename.
     */
    private void sendFcmNotification(User user, String title, String body, String type, java.util.Map<String, String> extraData, String soundName) {
        if (user == null || user.getFcmToken() == null || user.getFcmToken().isEmpty()) {
            if (user != null) {
                System.out.println("[FCM] Skipped: user " + user.getId() + " (" + user.getPhone() + ") has no fcmToken stored");
            }
            return;
        }
        try {
            String androidChannelId = soundName != null ? soundName + "_channel" : "high_importance_channel";

            com.google.firebase.messaging.Message.Builder builder = com.google.firebase.messaging.Message.builder()
                    .setToken(user.getFcmToken())
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder()
                            .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                            .setNotification(com.google.firebase.messaging.AndroidNotification.builder()
                                    .setChannelId(androidChannelId)
                                    .build())
                            .build())
                    .putData("type", type);

            if (soundName != null) {
                builder.setApnsConfig(com.google.firebase.messaging.ApnsConfig.builder()
                        .setAps(com.google.firebase.messaging.Aps.builder()
                                .setSound(soundName + ".caf")
                                .build())
                        .build());
            }

            if (extraData != null) {
                for (java.util.Map.Entry<String, String> entry : extraData.entrySet()) {
                    builder.putData(entry.getKey(), entry.getValue());
                }
            }

            String messageId = com.google.firebase.messaging.FirebaseMessaging.getInstance().send(builder.build());
            System.out.println("[FCM] Sent to user " + user.getId() + " (" + user.getPhone() + "), messageId=" + messageId);
        } catch (Exception e) {
            System.err.println("[FCM] Failed to send to user " + user.getId() + " (" + user.getPhone() + "): " + e.getMessage());
        }
    }
}

