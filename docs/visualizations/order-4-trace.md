# Order 4 Execution Trace

**Final State:** Shipped

## Command Summary

- Completed: 7

## FSM + Commands Sequence Diagram

```mermaid
%%{init: {'themeCSS': '.noteText { text-align: left !important; }'}}%%
sequenceDiagram
    participant FSM as Order-4
    participant CQ as CommandQueue
    participant W as Worker

    Note over FSM: Created
    FSM->>FSM: InitiatePayment
    Note over FSM: PaymentProcessing
    FSM->>CQ: enqueue(ProcessPayment)
    Note right of CQ: orderId=4<br/>customerId={redacted}<br/>customerName={redacted}<br/>petName=Whiskers<br/>amount=150.0<br/>paymentMethod={redacted}
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>FSM: PaymentSucceeded
    Note over FSM: Paid
    FSM->>CQ: enqueue(RequestShipping)
    Note right of CQ: orderId=4<br/>petName=Whiskers<br/>customerName={redacted}<br/>customerAddress={redacted}<br/>correlationId=404ff617-6e3b-47b9-872f-f0232b0ba623
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(SendNotification)
    Note right of CQ: orderId=4<br/>customerEmail={redacted}<br/>customerName={redacted}<br/>petName=Whiskers<br/>notificationType=order_confirmed<br/>messageId=fd2c76e1-23c0-4438-9933-e48a857d4346
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>FSM: RequestShipping
    Note over FSM: ShippingRequested
    FSM->>FSM: ShipmentDispatched
    Note over FSM: Shipped
    FSM->>CQ: enqueue(NotificationCallback)
    Note right of CQ: messageId=fd2c76e1-23c0-4438-9933-e48a857d4346<br/>delivered=true<br/>error=None
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(ShippingCallback)
    Note right of CQ: correlationId=404ff617-6e3b-47b9-872f-f0232b0ba623<br/>trackingNumber=TRACK-206515<br/>carrier=FurryFriends Delivery<br/>estimatedDelivery=3 business days<br/>success=true<br/>error=None
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(SendNotification)
    Note right of CQ: orderId=4<br/>customerEmail={redacted}<br/>customerName={redacted}<br/>petName=Whiskers<br/>notificationType=shipped<br/>messageId=fd2c76e1-23c0-4438-9933-e48a857d4346-shipped
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(NotificationCallback)
    Note right of CQ: messageId=fd2c76e1-23c0-4438-9933-e48a857d4346-shipped<br/>delivered=true<br/>error=None
    CQ->>W: claim
    W->>CQ: ✅ Completed
    Note over FSM: Current: Shipped
```

## FSM-Only Sequence Diagram

```mermaid
sequenceDiagram
    participant FSM as Order-4
    Note over FSM: Created
    FSM->>FSM: InitiatePayment
    Note over FSM: PaymentProcessing
    FSM->>FSM: PaymentSucceeded
    Note over FSM: Paid
    FSM->>FSM: RequestShipping
    Note over FSM: ShippingRequested
    FSM->>FSM: ShipmentDispatched
    Note over FSM: Shipped
    Note over FSM: Current: Shipped
```

## Flowchart with Commands

```mermaid
flowchart TB
    subgraph FSM["🔄 FSM States"]
        direction LR
        Created(("🆕 Created"))
        PaymentProcessing(("⏳ PaymentProcessing"))
        Paid(("💰 Paid"))
        ShippingRequested(("⏳ ShippingRequested"))
        Shipped(("📦 Shipped"))
        Delivered(("✅ Delivered"))
        Cancelled(("❌ Cancelled"))
        Created -->|InitiatePayment| PaymentProcessing
        PaymentProcessing -->|PaymentSucceeded| Paid
        PaymentProcessing -->|PaymentFailed| Cancelled
        Paid -->|RequestShipping| ShippingRequested
        ShippingRequested -->|ShipmentDispatched| Shipped
        Shipped -->|DeliveryConfirmed| Delivered
    end

    subgraph Commands["⚡ Commands Triggered"]
        direction LR
        ProcessPayment["💳 ProcessPayment"]
        RequestShipping["🚚 RequestShipping"]
        SendNotification["📧 SendNotification"]
    end

    PaymentProcessing -.->|on entry| ProcessPayment
    Paid -.->|on entry| RequestShipping
    Paid -.->|on entry| SendNotification
    Shipped -.->|on entry| SendNotification

    style Delivered fill:#98FB98,stroke:#228B22,stroke-width:2px
    style Cancelled fill:#FFB6C1,stroke:#DC143C,stroke-width:2px
    style ProcessPayment fill:#FFD700,stroke:#DAA520,stroke-width:2px
    style RequestShipping fill:#87CEEB,stroke:#4682B4,stroke-width:2px
    style SendNotification fill:#DDA0DD,stroke:#9932CC,stroke-width:2px

    style Created fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style ShippingRequested fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style PaymentProcessing fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style Shipped fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style Paid fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style Shipped fill:#90EE90,stroke:#228B22,stroke-width:4px
```
