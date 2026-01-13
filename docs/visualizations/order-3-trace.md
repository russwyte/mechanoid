# Order 3 Execution Trace

**Final State:** Paid

## Command Summary

- Completed: 3
- Failed: 1

## FSM + Commands Sequence Diagram

```mermaid
%%{init: {'themeCSS': '.noteText { text-align: left !important; }'}}%%
sequenceDiagram
    participant FSM as Order-3
    participant CQ as CommandQueue
    participant W as Worker

    Note over FSM: Created
    FSM->>FSM: InitiatePayment(25.0,Visa ****4242)
    Note over FSM: PaymentProcessing
    FSM->>CQ: enqueue(ProcessPayment)
    Note right of CQ: orderId=3<br/>customerId={redacted}<br/>customerName={redacted}<br/>petName=Goldie<br/>amount=25.0<br/>paymentMethod={redacted}
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>FSM: PaymentSucceeded(TXN-687055)
    Note over FSM: Paid
    FSM->>CQ: enqueue(RequestShipping)
    Note right of CQ: orderId=3<br/>petName=Goldie<br/>customerName={redacted}<br/>customerAddress={redacted}<br/>correlationId=dab79dd1-d4ac-4977-ab43-ed79a56cca4d
    CQ->>W: claim
    W->>CQ: ❌ Failed
    FSM->>CQ: enqueue(SendNotification)
    Note right of CQ: orderId=3<br/>customerEmail={redacted}<br/>customerName={redacted}<br/>petName=Goldie<br/>notificationType=order_confirmed<br/>messageId=de6fcabd-d4cd-445a-8817-47c7a600f25a
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(NotificationCallback)
    Note right of CQ: messageId=de6fcabd-d4cd-445a-8817-47c7a600f25a<br/>delivered=true<br/>error=None
    CQ->>W: claim
    W->>CQ: ✅ Completed
    Note over FSM: Current: Paid
```

## FSM-Only Sequence Diagram

```mermaid
sequenceDiagram
    participant FSM as Order-3
    Note over FSM: Created
    FSM->>FSM: InitiatePayment(25.0,Visa ****4242)
    Note over FSM: PaymentProcessing
    FSM->>FSM: PaymentSucceeded(TXN-687055)
    Note over FSM: Paid
    Note over FSM: Current: Paid
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
    style PaymentProcessing fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style Paid fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style Paid fill:#90EE90,stroke:#228B22,stroke-width:4px
```
