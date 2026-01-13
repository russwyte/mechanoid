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
    FSM->>FSM: InitiatePayment(250.0,Visa ****4242)
    Note over FSM: PaymentProcessing
    FSM->>CQ: enqueue(ProcessPayment)
    Note right of CQ: orderId=4<br/>customerId={redacted}<br/>customerName={redacted}<br/>petName=Buddy<br/>amount=250.0<br/>paymentMethod={redacted}
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>FSM: PaymentSucceeded(TXN-761792)
    Note over FSM: Paid
    FSM->>CQ: enqueue(RequestShipping)
    Note right of CQ: orderId=4<br/>petName=Buddy<br/>customerName={redacted}<br/>customerAddress={redacted}<br/>correlationId=9b2434a7-fa73-4aa6-8581-4d8adf7461f9
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(SendNotification)
    Note right of CQ: orderId=4<br/>customerEmail={redacted}<br/>customerName={redacted}<br/>petName=Buddy<br/>notificationType=order_confirmed<br/>messageId=d2d215e8-d316-4c2b-ba4b-168480e8dbf8
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>FSM: RequestShipping(789 Pine Rd, Lakewood)
    Note over FSM: ShippingRequested
    FSM->>FSM: ShipmentDispatched(...)
    Note over FSM: Shipped
    FSM->>CQ: enqueue(NotificationCallback)
    Note right of CQ: messageId=d2d215e8-d316-4c2b-ba4b-168480e8dbf8<br/>delivered=true<br/>error=None
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(ShippingCallback)
    Note right of CQ: correlationId=9b2434a7-fa73-4aa6-8581-4d8adf7461f9<br/>trackingNumber=TRACK-337284<br/>carrier=AnimalCare Logistics<br/>estimatedDelivery=2 business days<br/>success=true<br/>error=None
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(SendNotification)
    Note right of CQ: orderId=4<br/>customerEmail={redacted}<br/>customerName={redacted}<br/>petName=Buddy<br/>notificationType=shipped<br/>messageId=d2d215e8-d316-4c2b-ba4b-168480e8dbf8-shipped
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(NotificationCallback)
    Note right of CQ: messageId=d2d215e8-d316-4c2b-ba4b-168480e8dbf8-shipped<br/>delivered=true<br/>error=None
    CQ->>W: claim
    W->>CQ: ✅ Completed
    Note over FSM: Current: Shipped
```

## FSM-Only Sequence Diagram

```mermaid
sequenceDiagram
    participant FSM as Order-4
    Note over FSM: Created
    FSM->>FSM: InitiatePayment(250.0,Visa ****4242)
    Note over FSM: PaymentProcessing
    FSM->>FSM: PaymentSucceeded(TXN-761792)
    Note over FSM: Paid
    FSM->>FSM: RequestShipping(789 Pine Rd, Lakewood)
    Note over FSM: ShippingRequested
    FSM->>FSM: ShipmentDispatched(...)
    Note over FSM: Shipped
    Note over FSM: Current: Shipped
```

## Flowchart with Commands

```mermaid
flowchart TB
    subgraph FSM["🔄 FSM States"]
        direction LR
        Shipped(("📦 Shipped"))
        PaymentProcessing(("⏳ PaymentProcessing"))
        ShippingRequested(("⏳ ShippingRequested"))
        Delivered(("✅ Delivered"))
        Cancelled(("❌ Cancelled"))
        Paid(("💰 Paid"))
        Created(("🆕 Created"))
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
