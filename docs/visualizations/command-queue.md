# Pet Store Command Processing Report

## Summary

| Status | Count |
|--------|-------|
| ⏳ Pending | 0 |
| ⚙️ Processing | 0 |
| ✅ Completed | 23 |
| ❌ Failed | 2 |
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

    cmd0 -->|6| completed
    cmd1 -->|4| completed
    cmd1 -->|1| failed
    cmd2 -->|3| completed
    cmd2 -->|1| failed
    cmd3 -->|7| completed
    cmd4 -->|3| completed
```

## Commands by Instance

### Instance: 1

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 1 | ProcessPayment(orderId=1, customerId={redacted}, customerName={redacted}, petName=Whiskers, amount=150.0, paymentMethod={redacted}) | ✅ Completed | 1 | 15:30:50.762 |
| 2 | RequestShipping(orderId=1, petName=Whiskers, customerName={redacted}, customerAddress={redacted}, correlationId=6af53de1-d7c1-4785-adb6-5a0e32c203c3) | ✅ Completed | 1 | 15:30:51.041 |
| 3 | SendNotification(orderId=1, customerEmail={redacted}, customerName={redacted}, petName=Whiskers, notificationType=order_confirmed, messageId=bb8e9bf3-e064-4c36-a942-6589e2d7a4d4) | ✅ Completed | 1 | 15:30:51.041 |
| 7 | ShippingCallback(correlationId=6af53de1-d7c1-4785-adb6-5a0e32c203c3, trackingNumber=TRACK-799082, carrier=FurryFriends Delivery, estimatedDelivery=3 business days, success=true, error=None) | ✅ Completed | 1 | 15:30:52.467 |
| 8 | SendNotification(orderId=1, customerEmail={redacted}, customerName={redacted}, petName=Whiskers, notificationType=shipped, messageId=bb8e9bf3-e064-4c36-a942-6589e2d7a4d4-shipped) | ✅ Completed | 2 | 15:30:52.832 |
| 18 | NotificationCallback(messageId=bb8e9bf3-e064-4c36-a942-6589e2d7a4d4-shipped, delivered=true, error=None) | ✅ Completed | 1 | 15:30:55.634 |

### Instance: 2

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 4 | ProcessPayment(orderId=2, customerId={redacted}, customerName={redacted}, petName=Whiskers, amount=150.0, paymentMethod={redacted}) | ✅ Completed | 1 | 15:30:52.029 |
| 5 | RequestShipping(orderId=2, petName=Whiskers, customerName={redacted}, customerAddress={redacted}, correlationId=76ccc764-3e9c-44a7-b2ba-9754d3046ce0) | ✅ Completed | 1 | 15:30:52.340 |
| 6 | SendNotification(orderId=2, customerEmail={redacted}, customerName={redacted}, petName=Whiskers, notificationType=order_confirmed, messageId=31c4084d-5e5a-45f5-b787-5692059f9c8f) | ✅ Completed | 1 | 15:30:52.340 |
| 9 | NotificationCallback(messageId=31c4084d-5e5a-45f5-b787-5692059f9c8f, delivered=true, error=None) | ✅ Completed | 1 | 15:30:53.082 |
| 11 | ShippingCallback(correlationId=76ccc764-3e9c-44a7-b2ba-9754d3046ce0, trackingNumber=TRACK-952525, carrier=PetExpress, estimatedDelivery=4 business days, success=true, error=None) | ✅ Completed | 1 | 15:30:53.595 |
| 14 | SendNotification(orderId=2, customerEmail={redacted}, customerName={redacted}, petName=Whiskers, notificationType=shipped, messageId=31c4084d-5e5a-45f5-b787-5692059f9c8f-shipped) | ✅ Completed | 1 | 15:30:53.986 |
| 16 | NotificationCallback(messageId=31c4084d-5e5a-45f5-b787-5692059f9c8f-shipped, delivered=true, error=None) | ✅ Completed | 1 | 15:30:54.460 |

### Instance: 3

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 10 | ProcessPayment(orderId=3, customerId={redacted}, customerName={redacted}, petName=Tweety, amount=75.0, paymentMethod={redacted}) | ✅ Completed | 1 | 15:30:53.290 |
| 12 | RequestShipping(orderId=3, petName=Tweety, customerName={redacted}, customerAddress={redacted}, correlationId=4a2b00d7-b1b7-44b9-9dc3-a88d967739d3) | ❌ Failed | 1 | 15:30:53.721 |
|   | ↳ Error: AddressInvalid(Could not validate address) |   |   |   |
| 13 | SendNotification(orderId=3, customerEmail={redacted}, customerName={redacted}, petName=Tweety, notificationType=order_confirmed, messageId=62ba75ad-b96c-4a3a-b14e-db70fe2806a9) | ✅ Completed | 1 | 15:30:53.721 |
| 15 | NotificationCallback(messageId=62ba75ad-b96c-4a3a-b14e-db70fe2806a9, delivered=true, error=None) | ✅ Completed | 1 | 15:30:54.356 |

### Instance: 4

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 17 | ProcessPayment(orderId=4, customerId={redacted}, customerName={redacted}, petName=Goldie, amount=25.0, paymentMethod={redacted}) | ❌ Failed | 2 | 15:30:54.549 |
|   | ↳ Error: CardDeclined(Generic decline) |   |   |   |

### Instance: 5

| # | Command | Status | Attempts | Enqueued |
|---|---------|--------|----------|----------|
| 19 | ProcessPayment(orderId=5, customerId={redacted}, customerName={redacted}, petName=Hoppy, amount=100.0, paymentMethod={redacted}) | ✅ Completed | 1 | 15:30:55.810 |
| 20 | RequestShipping(orderId=5, petName=Hoppy, customerName={redacted}, customerAddress={redacted}, correlationId=e767b159-add2-4caa-b7db-a8b406620f64) | ✅ Completed | 1 | 15:30:56.144 |
| 21 | SendNotification(orderId=5, customerEmail={redacted}, customerName={redacted}, petName=Hoppy, notificationType=order_confirmed, messageId=6d1f019d-9b1e-45e7-8ee2-3a3b489fdb52) | ✅ Completed | 1 | 15:30:56.144 |
| 22 | NotificationCallback(messageId=6d1f019d-9b1e-45e7-8ee2-3a3b489fdb52, delivered=true, error=None) | ✅ Completed | 1 | 15:30:56.896 |
| 23 | ShippingCallback(correlationId=e767b159-add2-4caa-b7db-a8b406620f64, trackingNumber=TRACK-95018, carrier=FurryFriends Delivery, estimatedDelivery=4 business days, success=true, error=None) | ✅ Completed | 1 | 15:30:57.520 |
| 24 | SendNotification(orderId=5, customerEmail={redacted}, customerName={redacted}, petName=Hoppy, notificationType=shipped, messageId=6d1f019d-9b1e-45e7-8ee2-3a3b489fdb52-shipped) | ✅ Completed | 1 | 15:30:57.533 |
| 25 | NotificationCallback(messageId=6d1f019d-9b1e-45e7-8ee2-3a3b489fdb52-shipped, delivered=true, error=None) | ✅ Completed | 1 | 15:30:58.088 |

