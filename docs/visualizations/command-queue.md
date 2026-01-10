# Pet Store Command Processing Report

## Summary

| Status | Count |
|--------|-------|
| ⏳ Pending | 0 |
| ⚙️ Processing | 0 |
| ✅ Completed | 28 |
| ❌ Failed | 1 |
| ⏭️ Skipped | 0 |

## Command Flow

```mermaid
flowchart LR
    cmd0["📧 NotificationCallback"]
    cmd1["💳 ProcessPayment"]
    cmd2["🚚 RequestShipping"]
    cmd3["📧 SendNotification"]
    cmd4["🚚 ShippingCallback"]

    completed(("✅ Completed"))
    failed(("❌ Failed"))
    pending(("⏳ Pending"))

    style completed fill:#90EE90,stroke:#228B22,stroke-width:2px
    style failed fill:#FFB6C1,stroke:#DC143C,stroke-width:2px
    style pending fill:#FFFFE0,stroke:#DAA520,stroke-width:2px

    style cmd0 fill:#DDA0DD,stroke:#9932CC,stroke-width:2px
    style cmd1 fill:#FFD700,stroke:#DAA520,stroke-width:2px
    style cmd2 fill:#87CEEB,stroke:#4682B4,stroke-width:2px
    style cmd3 fill:#DDA0DD,stroke:#9932CC,stroke-width:2px
    style cmd4 fill:#87CEEB,stroke:#4682B4,stroke-width:2px

    cmd0 -->|8| completed
    cmd1 -->|4| completed
    cmd1 -->|1| failed
    cmd2 -->|4| completed
    cmd3 -->|8| completed
    cmd4 -->|4| completed
```

## Commands by Instance

### Instance: 1

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 1 | ProcessPayment(orderId=1, customerId={redacted}, customerName={redacted}, petName=Tweety, amount=75.0, paymentMethod={redacted}) | ✅ Completed | 1 | 11:52:43.554 |
| 2 | RequestShipping(orderId=1, petName=Tweety, customerName={redacted}, customerAddress={redacted}, correlationId=ef767adb-e434-4e35-9bc6-b232b29e6a27) | ✅ Completed | 1 | 11:52:43.764 |
| 3 | SendNotification(orderId=1, customerEmail={redacted}, customerName={redacted}, petName=Tweety, notificationType=order_confirmed, messageId=684782af-3bd0-4837-9395-ee2d49ef1e84) | ✅ Completed | 1 | 11:52:43.764 |
| 4 | ShippingCallback(correlationId=ef767adb-e434-4e35-9bc6-b232b29e6a27, trackingNumber=TRACK-707522, carrier=PetExpress, estimatedDelivery=6 business days, success=true, error=None) | ✅ Completed | 1 | 11:52:44.600 |
| 5 | SendNotification(orderId=1, customerEmail={redacted}, customerName={redacted}, petName=Tweety, notificationType=shipped, messageId=684782af-3bd0-4837-9395-ee2d49ef1e84-shipped) | ✅ Completed | 1 | 11:52:44.646 |
| 6 | NotificationCallback(messageId=684782af-3bd0-4837-9395-ee2d49ef1e84, delivered=true, error=None) | ✅ Completed | 1 | 11:52:44.665 |
| 8 | NotificationCallback(messageId=684782af-3bd0-4837-9395-ee2d49ef1e84-shipped, delivered=true, error=None) | ✅ Completed | 1 | 11:52:45.074 |

### Instance: 2

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 7 | ProcessPayment(orderId=2, customerId={redacted}, customerName={redacted}, petName=Goldie, amount=25.0, paymentMethod={redacted}) | ✅ Completed | 1 | 11:52:44.818 |
| 9 | RequestShipping(orderId=2, petName=Goldie, customerName={redacted}, customerAddress={redacted}, correlationId=5e5cc7f8-bc9f-4c84-9cae-729155aabf11) | ✅ Completed | 1 | 11:52:45.113 |
| 10 | SendNotification(orderId=2, customerEmail={redacted}, customerName={redacted}, petName=Goldie, notificationType=order_confirmed, messageId=213cf24d-6653-40e5-9e3b-0a5c99353e40) | ✅ Completed | 1 | 11:52:45.113 |
| 11 | NotificationCallback(messageId=213cf24d-6653-40e5-9e3b-0a5c99353e40, delivered=true, error=None) | ✅ Completed | 1 | 11:52:45.581 |
| 13 | ShippingCallback(correlationId=5e5cc7f8-bc9f-4c84-9cae-729155aabf11, trackingNumber=TRACK-870952, carrier=AnimalCare Logistics, estimatedDelivery=4 business days, success=true, error=None) | ✅ Completed | 1 | 11:52:46.626 |
| 14 | SendNotification(orderId=2, customerEmail={redacted}, customerName={redacted}, petName=Goldie, notificationType=shipped, messageId=213cf24d-6653-40e5-9e3b-0a5c99353e40-shipped) | ✅ Completed | 1 | 11:52:46.706 |
| 16 | NotificationCallback(messageId=213cf24d-6653-40e5-9e3b-0a5c99353e40-shipped, delivered=true, error=None) | ✅ Completed | 1 | 11:52:47.542 |

### Instance: 3

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 12 | ProcessPayment(orderId=3, customerId={redacted}, customerName={redacted}, petName=Hoppy, amount=100.0, paymentMethod={redacted}) | ❌ Failed | 1 | 11:52:46.079 |
|   | ↳ Error: FraudCheckFailed |   |   |   |

### Instance: 4

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 15 | ProcessPayment(orderId=4, customerId={redacted}, customerName={redacted}, petName=Goldie, amount=25.0, paymentMethod={redacted}) | ✅ Completed | 1 | 11:52:47.338 |
| 17 | RequestShipping(orderId=4, petName=Goldie, customerName={redacted}, customerAddress={redacted}, correlationId=f7d8cfa1-f80a-4c4a-91cd-afd32a81175d) | ✅ Completed | 1 | 11:52:47.759 |
| 18 | SendNotification(orderId=4, customerEmail={redacted}, customerName={redacted}, petName=Goldie, notificationType=order_confirmed, messageId=95ecf72f-ac34-4c8a-a436-6275ce637b52) | ✅ Completed | 1 | 11:52:47.759 |
| 19 | NotificationCallback(messageId=95ecf72f-ac34-4c8a-a436-6275ce637b52, delivered=true, error=None) | ✅ Completed | 1 | 11:52:48.438 |
| 21 | ShippingCallback(correlationId=f7d8cfa1-f80a-4c4a-91cd-afd32a81175d, trackingNumber=TRACK-709793, carrier=FurryFriends Delivery, estimatedDelivery=5 business days, success=true, error=None) | ✅ Completed | 1 | 11:52:48.922 |
| 24 | SendNotification(orderId=4, customerEmail={redacted}, customerName={redacted}, petName=Goldie, notificationType=shipped, messageId=95ecf72f-ac34-4c8a-a436-6275ce637b52-shipped) | ✅ Completed | 1 | 11:52:49.154 |
| 26 | NotificationCallback(messageId=95ecf72f-ac34-4c8a-a436-6275ce637b52-shipped, delivered=true, error=None) | ✅ Completed | 1 | 11:52:49.915 |

### Instance: 5

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 20 | ProcessPayment(orderId=5, customerId={redacted}, customerName={redacted}, petName=Buddy, amount=250.0, paymentMethod={redacted}) | ✅ Completed | 1 | 11:52:48.597 |
| 22 | RequestShipping(orderId=5, petName=Buddy, customerName={redacted}, customerAddress={redacted}, correlationId=cd146227-4b39-45a2-a896-e33f989ed53a) | ✅ Completed | 1 | 11:52:49.025 |
| 23 | SendNotification(orderId=5, customerEmail={redacted}, customerName={redacted}, petName=Buddy, notificationType=order_confirmed, messageId=56b9d5bc-7b66-4a30-ba1d-bd9e8fdcaa4b) | ✅ Completed | 1 | 11:52:49.025 |
| 25 | NotificationCallback(messageId=56b9d5bc-7b66-4a30-ba1d-bd9e8fdcaa4b, delivered=true, error=None) | ✅ Completed | 1 | 11:52:49.807 |
| 27 | ShippingCallback(correlationId=cd146227-4b39-45a2-a896-e33f989ed53a, trackingNumber=TRACK-634617, carrier=PetExpress, estimatedDelivery=6 business days, success=true, error=None) | ✅ Completed | 1 | 11:52:50.304 |
| 28 | SendNotification(orderId=5, customerEmail={redacted}, customerName={redacted}, petName=Buddy, notificationType=shipped, messageId=56b9d5bc-7b66-4a30-ba1d-bd9e8fdcaa4b-shipped) | ✅ Completed | 1 | 11:52:50.348 |
| 29 | NotificationCallback(messageId=56b9d5bc-7b66-4a30-ba1d-bd9e8fdcaa4b-shipped, delivered=true, error=None) | ✅ Completed | 1 | 11:52:50.881 |

