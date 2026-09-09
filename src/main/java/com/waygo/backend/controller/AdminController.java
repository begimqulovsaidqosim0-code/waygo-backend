package com.waygo.backend.controller;

import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.Transaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.repository.TransactionRepository;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.service.BackupService;
import com.waygo.backend.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final SystemSettingsService settingsService;
    private final com.waygo.backend.service.MapSettingsService mapSettingsService;
    private final BackupService backupService;
    private final TransactionRepository transactionRepository;
    private final com.waygo.backend.service.TransactionService transactionService;
    private final com.waygo.backend.service.NotificationService notificationService;
    private final com.waygo.backend.repository.config.TariffPlanRepository tariffPlanRepository;
    private final com.waygo.backend.service.FileService fileService;
    private final com.waygo.backend.service.AdminUserService adminUserService;

    @GetMapping("/login")
    public String login() {
        return "admin/login";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        // ... (existing code)
        long driverCount = userRepository.countByRole(User.Role.DRIVER);
        long passengerCount = userRepository.countByRole(User.Role.PASSENGER);
        long activeOrders = orderRepository.countByStatus(Order.OrderStatus.PENDING) 
                          + orderRepository.countByStatus(Order.OrderStatus.ACCEPTED)
                          + orderRepository.countByStatus(Order.OrderStatus.STARTED);
        
        List<Order> latestOrders = orderRepository.findTop10ByOrderByCreatedAtDesc();

        model.addAttribute("title", "WayGO Admin Dashboard");
        model.addAttribute("driverCount", driverCount);
        model.addAttribute("passengerCount", passengerCount);
        model.addAttribute("activeOrders", activeOrders);
        model.addAttribute("latestOrders", latestOrders);
        model.addAttribute("activeItem", "dashboard");
        
        return "admin/dashboard";
    }

    @GetMapping("/payments")
    public String payments(
            @org.springframework.web.bind.annotation.RequestParam(value = "startDate", required = false) String startDateStr,
            @org.springframework.web.bind.annotation.RequestParam(value = "endDate", required = false) String endDateStr,
            Model model) {
        
        List<Transaction> tariffPurchases;
        boolean isFiltered = false;
        BigDecimal filteredRevenue = BigDecimal.ZERO;
        long filteredCount = 0;
        
        LocalDateTime start = null;
        LocalDateTime end = null;
        
        if (startDateStr != null && !startDateStr.trim().isEmpty()) {
            try {
                start = java.time.LocalDate.parse(startDateStr.trim()).atStartOfDay();
            } catch (Exception ignored) {}
        }
        if (endDateStr != null && !endDateStr.trim().isEmpty()) {
            try {
                end = java.time.LocalDate.parse(endDateStr.trim()).atTime(23, 59, 59, 999999000);
            } catch (Exception ignored) {}
        }
        
        if (start != null || end != null) {
            isFiltered = true;
            if (start == null) {
                start = LocalDateTime.of(1970, 1, 1, 0, 0);
            }
            if (end == null) {
                end = LocalDateTime.now();
            }
            tariffPurchases = transactionRepository
                    .findByTypeAndCreatedAtBetweenOrderByCreatedAtDesc(Transaction.TransactionType.TARIFF_PURCHASE, start, end);
            filteredRevenue = transactionRepository
                    .sumAmountByTypeAndCreatedAtBetween(Transaction.TransactionType.TARIFF_PURCHASE, start, end);
            filteredCount = transactionRepository
                    .countByTypeAndCreatedAtBetween(Transaction.TransactionType.TARIFF_PURCHASE, start, end);
        } else {
            tariffPurchases = transactionRepository
                    .findByTypeOrderByCreatedAtDesc(Transaction.TransactionType.TARIFF_PURCHASE);
        }

        // Statistika
        BigDecimal totalRevenue = transactionRepository
                .sumAmountByType(Transaction.TransactionType.TARIFF_PURCHASE);
        long totalCount = transactionRepository
                .countByType(Transaction.TransactionType.TARIFF_PURCHASE);
        BigDecimal todayRevenue = transactionRepository
                .sumTariffRevenueFrom(LocalDateTime.now().withHour(0).withMinute(0).withSecond(0));
        BigDecimal monthRevenue = transactionRepository
                .sumTariffRevenueFrom(LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0));

        model.addAttribute("title", "WayGO To'lovlar Statistikasi");
        model.addAttribute("tariffPurchases", tariffPurchases);
        model.addAttribute("totalRevenue", totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("todayRevenue", todayRevenue != null ? todayRevenue : BigDecimal.ZERO);
        model.addAttribute("monthRevenue", monthRevenue != null ? monthRevenue : BigDecimal.ZERO);
        
        // Filter information
        model.addAttribute("isFiltered", isFiltered);
        model.addAttribute("startDate", startDateStr);
        model.addAttribute("endDate", endDateStr);
        model.addAttribute("filteredRevenue", filteredRevenue != null ? filteredRevenue : BigDecimal.ZERO);
        model.addAttribute("filteredCount", filteredCount);
        
        model.addAttribute("activeItem", "payments");
        return "admin/payments";
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("title", "WayGO Tizim Sozlamalari");
        model.addAttribute("settings", settingsService.getSettings());
        model.addAttribute("backups", backupService.listBackups());
        model.addAttribute("activeItem", "settings");
        return "admin/settings";
    }

    @GetMapping("/map-settings")
    public String mapSettings(Model model) {
        model.addAttribute("title", "Xarita Sozlamalari (Yandex Map)");
        model.addAttribute("settings", mapSettingsService.getSettings());
        model.addAttribute("activeItem", "map_settings");
        return "admin/map-settings";
    }

    @org.springframework.web.bind.annotation.PostMapping("/map-settings")
    public String updateMapSettings(
            @org.springframework.web.bind.annotation.ModelAttribute com.waygo.backend.entity.MapSettings settings,
            @org.springframework.web.bind.annotation.RequestParam(value = "markerImageFile", required = false)
            org.springframework.web.multipart.MultipartFile markerImageFile) {
        if (markerImageFile != null && !markerImageFile.isEmpty()) {
            try {
                String fileName = fileService.saveFile(markerImageFile);
                settings.setDriverMarkerImageUrl("/uploads/" + fileName);
            } catch (java.io.IOException e) {
                // Log and ignore — save proceeds with the previous image untouched.
            }
        }
        com.waygo.backend.entity.MapSettings saved = mapSettingsService.updateSettings(settings);
        notificationService.notifyMapSettingsUpdated(saved);
        return "redirect:/admin/map-settings?success";
    }

    @GetMapping("/backup/download")
    public ResponseEntity<byte[]> downloadBackup() {
        // ... (existing code for on-demand JSON)
        try {
            byte[] data = backupService.generateBackupJson();
            String filename = "waygo_backup_" + java.time.LocalDate.now() + ".json";
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/backup/file/{filename}")
    public ResponseEntity<byte[]> downloadBackupFile(@org.springframework.web.bind.annotation.PathVariable String filename) {
        try {
            java.io.File file = new java.io.File("backups/" + filename);
            if (!file.exists()) return ResponseEntity.notFound().build();
            
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(filename.endsWith(".sql") ? MediaType.APPLICATION_OCTET_STREAM : MediaType.APPLICATION_JSON)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/backup/sql")
    public ResponseEntity<byte[]> downloadSqlBackup() {
        try {
            byte[] data = backupService.generateSqlBackup();
            String filename = "waygo_db_backup_" + java.time.LocalDate.now() + ".sql";
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/drivers")
    public String drivers(Model model) {
        model.addAttribute("title", "WayGO Haydovchilar");
        List<User> drivers = userRepository.findByRoleOrderByCreatedAtDesc(User.Role.DRIVER);
        drivers.forEach(d -> d.setImageUrl(null));
        model.addAttribute("drivers", drivers);
        model.addAttribute("tariffs", tariffPlanRepository.findAllByIsActiveTrue());
        model.addAttribute("activeItem", "drivers");
        return "admin/drivers";
    }

    @GetMapping("/passengers")
    public String passengers(Model model) {
        model.addAttribute("title", "WayGO Mijozlar");
        List<User> passengers = userRepository.findByRoleOrderByCreatedAtDesc(User.Role.PASSENGER);
        passengers.forEach(p -> p.setImageUrl(null));
        model.addAttribute("passengers", passengers);
        model.addAttribute("activeItem", "passengers");
        return "admin/passengers";
    }

    @GetMapping("/orders")
    public String orders(
            @org.springframework.web.bind.annotation.RequestParam(value = "page", defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(value = "size", defaultValue = "20") int size,
            Model model) {
        org.springframework.data.domain.Page<Order> orderPage = orderRepository.findAll(
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
        model.addAttribute("title", "WayGO Barcha Buyurtmalar");
        model.addAttribute("orders", orderPage.getContent());
        model.addAttribute("page", orderPage);
        model.addAttribute("activeItem", "orders");
        return "admin/orders";
    }

    @GetMapping("/orders/{id}")
    public String orderDetail(@org.springframework.web.bind.annotation.PathVariable Long id, Model model) {
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return "redirect:/admin/orders";
        }
        model.addAttribute("title", "Buyurtma #" + order.getId());
        model.addAttribute("order", order);
        model.addAttribute("activeItem", "orders");
        return "admin/order-detail";
    }

    @org.springframework.web.bind.annotation.PostMapping("/settings")
    public String updateSettings(@org.springframework.web.bind.annotation.ModelAttribute com.waygo.backend.entity.SystemSettings settings) {
        settingsService.updateSettings(settings);
        return "redirect:/admin/settings?success";
    }

    @org.springframework.web.bind.annotation.PostMapping("/backup/import")
    public String importBackup(@org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            if (!file.isEmpty()) {
                backupService.restoreFromJson(file.getBytes());
                return "redirect:/admin/settings?importSuccess";
            }
        } catch (Exception e) {
            return "redirect:/admin/settings?importError=" + e.getMessage();
        }
        return "redirect:/admin/settings";
    }

    @org.springframework.web.bind.annotation.PostMapping("/orders/clear")
    @org.springframework.transaction.annotation.Transactional
    public String clearOrders() {
        try {
            orderRepository.deleteAll();
            return "redirect:/admin/settings?clearSuccess";
        } catch (Exception e) {
            return "redirect:/admin/settings?clearError=" + e.getMessage();
        }
    }

    private Object handleActionResponse(jakarta.servlet.http.HttpServletRequest request, Exception error) {
        return handleActionResponse(request, error, "/admin/drivers");
    }

    private Object handleActionResponse(jakarta.servlet.http.HttpServletRequest request, Exception error, String redirectBase) {
        boolean isXmlHttp = "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
        if (error != null) {
            if (isXmlHttp) {
                return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
            }
            return "redirect:" + redirectBase + "?error=" + error.getMessage();
        } else {
            if (isXmlHttp) {
                return ResponseEntity.ok(Map.of("success", true));
            }
            return "redirect:" + redirectBase + "?success";
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/delete")
    @org.springframework.transaction.annotation.Transactional
    public Object deleteDriver(@org.springframework.web.bind.annotation.PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User driver = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Haydovchi topilmadi"));
            if (driver.getRole() != User.Role.DRIVER) {
                throw new IllegalArgumentException("Bu foydalanuvchi haydovchi emas");
            }
            adminUserService.deleteUserCompletely(driver);
            return handleActionResponse(request, null, "/admin/drivers");
        } catch (Exception e) {
            return handleActionResponse(request, e, "/admin/drivers");
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/passengers/{id}/delete")
    @org.springframework.transaction.annotation.Transactional
    public Object deletePassenger(@org.springframework.web.bind.annotation.PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User passenger = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Mijoz topilmadi"));
            if (passenger.getRole() != User.Role.PASSENGER) {
                throw new IllegalArgumentException("Bu foydalanuvchi mijoz emas");
            }
            adminUserService.deleteUserCompletely(passenger);
            return handleActionResponse(request, null, "/admin/passengers");
        } catch (Exception e) {
            return handleActionResponse(request, e, "/admin/passengers");
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/reactivate")
    @org.springframework.transaction.annotation.Transactional
    public Object reactivateDriver(@org.springframework.web.bind.annotation.PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User driver = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Haydovchi topilmadi"));
            if (driver.getRole() != User.Role.DRIVER) {
                throw new IllegalArgumentException("Bu foydalanuvchi haydovchi emas");
            }
            reactivateUser(driver);
            return handleActionResponse(request, null, "/admin/drivers");
        } catch (Exception e) {
            return handleActionResponse(request, e, "/admin/drivers");
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/passengers/{id}/reactivate")
    @org.springframework.transaction.annotation.Transactional
    public Object reactivatePassenger(@org.springframework.web.bind.annotation.PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User passenger = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Mijoz topilmadi"));
            if (passenger.getRole() != User.Role.PASSENGER) {
                throw new IllegalArgumentException("Bu foydalanuvchi mijoz emas");
            }
            reactivateUser(passenger);
            return handleActionResponse(request, null, "/admin/passengers");
        } catch (Exception e) {
            return handleActionResponse(request, e, "/admin/passengers");
        }
    }

    // Restores the phone/email/fullName/imageUrl snapshotted at delete-account time
    // (see AuthController.deleteAccount) and clears the "deleted" flag. Fails loudly
    // if another account has since taken the original phone/email, rather than
    // silently overwriting that other account's uniqueness — the admin needs to
    // resolve that conflict with the user manually in that rare case.
    private void reactivateUser(User user) {
        if (Boolean.TRUE.equals(user.getActive())) {
            throw new IllegalArgumentException("Bu hisob allaqachon faol");
        }
        if (user.getDeletedOriginalPhone() == null) {
            throw new IllegalArgumentException("Bu hisob uchun tiklanadigan ma'lumot topilmadi");
        }
        userRepository.findByPhone(user.getDeletedOriginalPhone()).ifPresent(existing -> {
            if (!existing.getId().equals(user.getId())) {
                throw new IllegalArgumentException("Bu telefon raqami boshqa hisobda band");
            }
        });
        user.setPhone(user.getDeletedOriginalPhone());
        user.setEmail(user.getDeletedOriginalEmail());
        user.setFullName(user.getDeletedOriginalFullName());
        user.setImageUrl(user.getDeletedOriginalImageUrl());
        user.setDeletedOriginalPhone(null);
        user.setDeletedOriginalEmail(null);
        user.setDeletedOriginalFullName(null);
        user.setDeletedOriginalImageUrl(null);
        user.setActive(true);
        userRepository.save(user);
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/toggle-billing")
    @org.springframework.transaction.annotation.Transactional
    public Object toggleDriverBilling(@org.springframework.web.bind.annotation.PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        try {
            User driver = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Driver not found"));
            boolean newBillingState = !driver.isDriverBillingEnabled();
            driver.setDriverBillingEnabled(newBillingState);
            if (newBillingState) {
                driver.unfreezeTariff();
            } else {
                driver.freezeTariff();
            }
            User saved = userRepository.save(driver);
            
            try {
                String message = newBillingState 
                    ? "To'lov tizimi faollashtirildi. Iltimos, joriy tarif yoki balansni tekshiring." 
                    : "To'lov tizimi o'chirildi. Sizga VIP statusi berildi!";
                notificationService.notifyTariffUpdate(saved, message);
            } catch (Exception e) {
                // Ignore notification failure to prevent transaction rollback
            }
            
            return handleActionResponse(request, null);
        } catch (Exception e) {
            return handleActionResponse(request, e);
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/cancel-tariff")
    public Object cancelDriverTariff(@org.springframework.web.bind.annotation.PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        try {
            transactionService.cancelDriverTariff(id);
            return handleActionResponse(request, null);
        } catch (Exception e) {
            return handleActionResponse(request, e);
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/change-tariff")
    public Object changeDriverTariff(@org.springframework.web.bind.annotation.PathVariable Long id, @org.springframework.web.bind.annotation.RequestParam("tariffId") Long tariffId, jakarta.servlet.http.HttpServletRequest request) {
        try {
            transactionService.changeDriverTariff(id, tariffId);
            return handleActionResponse(request, null);
        } catch (Exception e) {
            return handleActionResponse(request, e);
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/assign-vip")
    public Object assignDriverVip(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam("price") java.math.BigDecimal price,
            @org.springframework.web.bind.annotation.RequestParam("durationDays") Integer durationDays,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            transactionService.assignManualVip(id, price, durationDays);
            return handleActionResponse(request, null);
        } catch (Exception e) {
            return handleActionResponse(request, e);
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/grant-trial")
    @org.springframework.transaction.annotation.Transactional
    public Object grantDriverTrial(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam("days") Integer days,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            User driver = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Driver not found"));
            driver.setDriverBillingEnabled(true);
            LocalDateTime baseTime = (driver.getTariffExpiryDate() != null && driver.getTariffExpiryDate().isAfter(LocalDateTime.now()))
                    ? driver.getTariffExpiryDate()
                    : LocalDateTime.now();
            driver.setTariffExpiryDate(baseTime.plusDays(days != null ? days : 14));
            User saved = userRepository.save(driver);
            try {
                notificationService.notifyTariffUpdate(saved, "Sizga " + days + " kunlik bepul sinov davri berildi!");
            } catch (Exception ignored) {}
            return handleActionResponse(request, null);
        } catch (Exception e) {
            return handleActionResponse(request, e);
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/bulk-action")
    @org.springframework.transaction.annotation.Transactional
    public Object bulkActionDrivers(
            @org.springframework.web.bind.annotation.RequestParam(value = "driverIds", required = false) List<Long> driverIds,
            @org.springframework.web.bind.annotation.RequestParam("action") String action,
            jakarta.servlet.http.HttpServletRequest request) {
        if (driverIds != null && !driverIds.isEmpty()) {
            try {
                boolean enable = "enable-billing".equals(action);
                List<User> drivers = userRepository.findAllById(driverIds);
                for (User driver : drivers) {
                    if (driver.getRole() == User.Role.DRIVER) {
                        if (driver.isDriverBillingEnabled() != enable) {
                            driver.setDriverBillingEnabled(enable);
                            if (enable) {
                                driver.unfreezeTariff();
                            } else {
                                driver.freezeTariff();
                            }
                            User saved = userRepository.save(driver);
                            
                            try {
                                String message = enable 
                                    ? "To'lov tizimi faollashtirildi. Iltimos, joriy tarif yoki balansni tekshiring." 
                                    : "To'lov tizimi o'chirildi. Sizga VIP statusi berildi!";
                                notificationService.notifyTariffUpdate(saved, message);
                            } catch (Exception notificationEx) {
                                // Ignore notification failure for individual driver
                            }
                        }
                    }
                }
                return handleActionResponse(request, null);
            } catch (Exception e) {
                return handleActionResponse(request, e);
            }
        }
        return handleActionResponse(request, null);
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/add-balance")
    @org.springframework.transaction.annotation.Transactional
    public Object addDriverBalance(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam("amount") java.math.BigDecimal amount,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            transactionService.topUp(id, amount);
            return handleActionResponse(request, null);
        } catch (Exception e) {
            return handleActionResponse(request, e);
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/reset-balance")
    public Object resetDriverBalance(@org.springframework.web.bind.annotation.PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        try {
            transactionService.resetBalance(id);
            return handleActionResponse(request, null);
        } catch (Exception e) {
            return handleActionResponse(request, e);
        }
    }
}

