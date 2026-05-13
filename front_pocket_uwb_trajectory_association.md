# Front-pocket UWB Tag 기반 Trajectory-level Face Association 설계안

## 0. 목표

이 문서는 **video streaming 상황에서 촬영자가 야외에서 움직이며 촬영할 때**, opt-out을 선택한 bystander의 얼굴을 실시간으로 blur하기 위한 핵심 문제를 정리한다.

특히 여기서는 UWB/BLE tag의 위치를 **바지 앞주머니(front pocket)** 로 고정한다고 가정한다. 이 가정은 기존의 “tag-to-face offset이 어디인지 모르는 문제”를 크게 단순화한다. 이제 시스템은 UWB가 측정하는 점을 “임의의 body tag”가 아니라 **앞주머니 위치**로 해석할 수 있고, face bbox와 UWB tag 사이의 관계를 더 강한 prior로 모델링할 수 있다.

핵심 목표는 다음과 같다.

> 200ms마다 CNN face detection을 수행하되, 그 사이 프레임에서는 IMU, UWB, lightweight visual tracking을 사용하여 20–30FPS blur를 유지하고, 여러 사람이 있을 때 각 face bbox와 opt-out UWB/BLE ID를 안정적으로 association한다.

---

## 1. 문제 정의

### 1.1 입력

시스템의 입력은 다음과 같다.

- Camera frame stream: 20–30FPS
- CNN face detection: 약 5Hz, 즉 200ms마다 수행
- UWB measurement: tag ID별 range, AoA, quality indicator
- BLE beacon: opt-out preference, UWB/BLE identity, optional metadata
- IMU: camera/anchor의 rotation, acceleration, gravity direction

### 1.2 출력

매 프레임마다 다음을 결정한다.

- 어떤 face track이 어떤 UWB/BLE opt-out ID에 대응하는가?
- 해당 face region을 blur할 것인가?
- association confidence가 낮을 때 어떤 후보 face까지 보수적으로 blur할 것인가?

### 1.3 핵심 문제

한 순간의 frame에서 face bbox와 UWB measurement를 바로 matching하기는 어렵다.

이유는 다음과 같다.

1. Face bbox는 2D image-plane 정보이다.
2. UWB는 3D relative position에 가까운 정보이다.
3. UWB가 측정하는 위치는 얼굴이 아니라 앞주머니이다.
4. 촬영자와 UWB anchor가 움직이므로 coordinate frame이 계속 변한다.
5. 여러 사람이 가까이 있거나 교차하면 single-frame geometry가 모호해진다.

따라서 이 문제는 다음과 같이 정의하는 것이 좋다.

> Sparse CNN detection으로 얻은 face trajectory와 front-pocket UWB trajectory를, IMU 기반 camera motion compensation과 front-pocket body prior를 사용하여 sliding-window에서 probabilistically association하는 문제.

---

## 2. Front-pocket tag 가정이 주는 장점

Tag 위치를 앞주머니로 고정하면 다음과 같은 장점이 있다.

### 2.1 Tag-to-face offset이 구조화된다

기존에는 tag가 목걸이, 손, 가방, 주머니 등 어디에 있을지 몰랐다. 이 경우 UWB point와 face bbox 사이의 offset이 너무 크고 불확실하다.

하지만 앞주머니로 고정하면 offset은 대략 다음 형태를 가진다.

- Tag는 face보다 아래쪽에 있다.
- Tag는 torso/pelvis 근처에 있다.
- Tag는 사람의 body와 함께 움직인다.
- Tag와 face의 3D 거리 및 image-plane offset이 비교적 안정적이다.
- Tag가 왼쪽 앞주머니인지 오른쪽 앞주머니인지 알 수 있으면 lateral uncertainty가 더 줄어든다.

따라서 더 이상 arbitrary offset을 추정할 필요가 없다. 대신 다음을 추정하면 된다.

- Face-to-pocket vertical offset
- Left/right pocket side
- Body orientation에 따른 lateral image offset
- 현재 camera viewpoint에서의 projected pocket location

### 2.2 Face bbox 아래에 pocket prior region을 만들 수 있다

Face bbox가 주어지면, 그 사람의 앞주머니는 image에서 대체로 face 아래쪽에 있어야 한다.

단, camera가 roll/tilt될 수 있으므로 단순히 image의 y-axis 아래쪽을 쓰면 안 된다. IMU에서 얻은 gravity direction을 image plane으로 project하여 **image 내의 아래 방향**을 정의하는 것이 좋다.

Face bbox center를 `c_i(t)`, bbox height를 `h_i(t)`, image-plane gravity direction을 `g_img(t)`라고 하면, 앞주머니 위치는 대략 다음 영역에 있어야 한다.

```text
pocket_region_i(t)
= c_i(t) + alpha * h_i(t) * g_img(t) + beta * h_i(t) * e_lat(t)
```

여기서:

- `alpha`: face에서 앞주머니까지의 vertical offset scale
- `beta`: 좌우 앞주머니에 따른 lateral offset scale
- `e_lat(t)`: image-plane gravity direction에 수직인 lateral direction

실제 구현에서는 하나의 점이 아니라 ellipse 또는 capsule 형태의 region으로 둔다.

```text
alpha ∈ [3.5, 6.5]
beta  ∈ [-1.2, 1.2]
```

위 범위는 초기값으로 사용할 수 있으며, 실제 데이터에서 calibration해야 한다.

### 2.3 UWB point에서 가능한 face region도 만들 수 있다

반대로 UWB가 앞주머니 위치를 측정한다고 보면, tag 위치에서 위쪽 방향으로 일정 거리 위에 face가 있어야 한다.

IMU를 사용해 camera coordinate에서 gravity vector를 알 수 있다면, UWB tag의 3D 위치 `p_tag^C(t)`로부터 가능한 face 위치를 다음처럼 표현할 수 있다.

```text
p_face^C(t) = p_tag^C(t) - d_fp * g_C(t) + lateral_offset
```

여기서:

- `p_tag^C(t)`: camera coordinate에서의 UWB front-pocket tag 위치
- `g_C(t)`: camera coordinate에서의 gravity direction
- `d_fp`: 앞주머니에서 face까지의 vertical physical distance
- `lateral_offset`: 왼쪽/오른쪽 주머니 및 body orientation에 따른 offset

`d_fp`는 사람의 키, 자세, camera viewpoint에 따라 달라지므로 고정값 하나가 아니라 prior distribution으로 두는 것이 좋다.

```text
d_fp ~ Normal(mu_d, sigma_d^2)
```

또는 실용적으로는 다음과 같은 range prior로 둔다.

```text
d_fp ∈ [0.7m, 1.2m]
```

이 범위 역시 실험 데이터로 조정해야 한다.

---

## 3. 전체 시스템 구조

추천하는 pipeline은 다음과 같다.

```text
Camera frames 20–30FPS
        |
        |-- every 200ms --> CNN face detector
        |                       |
        |                       v
        |                Visual face tracks
        |
        |-- every frame --> IMU-based ego-motion compensation
        |                       |
        |                       v
        |                Lightweight bbox propagation
        |
UWB/BLE stream ----------------> UWB tag tracks
        |                       |
        v                       v
  Opt-out ID            Front-pocket projected trajectory
        \                       /
         \                     /
          v                   v
     Sliding-window trajectory association
                    |
                    v
       Privacy-aware blur decision
                    |
                    v
          Blurred video stream
```

---

## 4. State representation

### 4.1 Visual face track

각 face track `i`는 다음 상태를 가진다.

```text
V_i(t) = {
  bbox_i(t),
  c_i(t),
  h_i(t),
  visual_velocity_i(t),
  detection_confidence_i(t),
  tracking_confidence_i(t)
}
```

여기서:

- `bbox_i(t)`: face bounding box
- `c_i(t)`: bbox center
- `h_i(t)`: bbox height
- `visual_velocity_i(t)`: image-plane velocity
- `detection_confidence_i(t)`: CNN detection confidence
- `tracking_confidence_i(t)`: CNN이 없는 frame에서 tracker가 유지한 confidence

### 4.2 UWB front-pocket tag track

각 UWB/BLE ID `j`는 다음 상태를 가진다.

```text
U_j(t) = {
  id_j,
  opt_out_j,
  p_tag_j^C(t),
  v_tag_j^C(t),
  range_j(t),
  aoa_j(t),
  uwb_quality_j(t),
  covariance_j(t),
  pocket_side_j
}
```

여기서:

- `p_tag_j^C(t)`: camera coordinate에서의 앞주머니 tag 위치
- `v_tag_j^C(t)`: camera coordinate에서의 tag velocity
- `covariance_j(t)`: UWB range/AoA uncertainty를 반영한 covariance
- `pocket_side_j`: left, right, unknown 중 하나

가능하다면 BLE payload나 사용자 설정에 `pocket_side_j`를 포함시키는 것이 좋다.

```text
pocket_side_j ∈ {left_front_pocket, right_front_pocket, unknown}
```

Pocket side를 알 수 있으면 association ambiguity가 줄어든다. 모르면 left/right mixture model로 처리한다.

---

## 5. Front-pocket prior model

### 5.1 Image-plane pocket prior from face bbox

Face bbox가 주어졌을 때, 해당 사람의 앞주머니가 있을 법한 image-plane region을 만든다.

```text
R_pocket_i(t)
= Ellipse(
    center = c_i(t) + alpha * h_i(t) * g_img(t) + beta_s * h_i(t) * e_lat(t),
    covariance = Sigma_pocket_i(t)
  )
```

여기서:

- `g_img(t)`: IMU로 얻은 image-plane gravity direction
- `e_lat(t)`: `g_img(t)`와 수직인 방향
- `alpha`: face-to-pocket vertical scale
- `beta_s`: pocket side에 따른 lateral scale
- `Sigma_pocket_i(t)`: face detector error, pose error, body proportion variation을 포함한 uncertainty

Pocket side별로는 다음처럼 둘 수 있다.

```text
right pocket: beta_s = +beta0
left pocket:  beta_s = -beta0
unknown:      mixture of left and right
```

단, 실제 image에서 좌우 부호는 camera convention과 mirror 여부에 맞춰 정의해야 한다.

### 5.2 3D face prior from UWB tag

UWB tag 위치가 앞주머니라고 보면 가능한 face 위치는 다음 distribution으로 표현할 수 있다.

```text
p_face_j^C(t)
= p_tag_j^C(t) - d_fp * g_C(t) + l_side * e_lat_body^C(t) + noise
```

하지만 `e_lat_body^C(t)`, 즉 body 좌우 방향은 직접 알기 어렵다. 따라서 초기 시스템에서는 lateral 방향을 강하게 고정하지 말고 covariance로 흡수한다.

실용적인 구현은 다음과 같다.

1. UWB tag position covariance에서 sample을 만든다.
2. `d_fp`를 range prior에서 sample한다.
3. lateral offset을 left/right/unknown prior에서 sample한다.
4. 각 sample을 camera image plane으로 project한다.
5. projected samples로 face prior ellipse를 fitting한다.

결과적으로 UWB tag `j`는 image plane에서 가능한 face region `R_face_j(t)`를 만든다.

```text
R_face_j(t) = ProjectedEllipse(p_tag_j^C(t), Sigma_j(t), front_pocket_prior)
```

---

## 6. Single-frame matching cost

한 frame에서 face track `i`와 UWB tag `j`의 compatibility는 여러 cost를 합쳐 계산한다.

```text
C_ij(t)
= lambda_pocket * E_pocket(i, j, t)
+ lambda_face   * E_face(i, j, t)
+ lambda_size   * E_size(i, j, t)
+ lambda_motion * E_motion(i, j, t)
+ lambda_quality * E_quality(j, t)
```

### 6.1 Pocket projection cost

UWB tag projection이 face bbox로부터 예측한 front-pocket region 안에 들어가는지 평가한다.

```text
E_pocket(i, j, t)
= MahalanobisDistance(
    project(p_tag_j^C(t)),
    R_pocket_i(t)
  )
```

이 항이 가장 중요하다.

기존의 잘못된 접근은 다음과 같다.

```text
face center와 UWB projection을 직접 비교
```

하지만 front-pocket tag에서는 이렇게 해야 한다.

```text
face bbox에서 예측한 앞주머니 region과 UWB projection을 비교
```

### 6.2 Face-from-tag cost

UWB tag로부터 예측한 가능한 face region과 실제 face bbox가 일치하는지 평가한다.

```text
E_face(i, j, t)
= MahalanobisDistance(
    c_i(t),
    R_face_j(t)
  )
```

이 항은 `E_pocket`의 inverse direction이다. 둘을 함께 쓰면 geometry consistency가 좋아진다.

### 6.3 Range-size consistency cost

UWB depth와 face bbox 크기가 대략 일관되는지 확인한다.

```text
h_i(t) ≈ f * H_face / Z_face_j(t)
```

여기서:

- `f`: camera focal length
- `H_face`: 평균 face physical height
- `Z_face_j(t)`: UWB tag 위치에서 front-pocket prior를 통해 추정한 face depth

이 항은 사람마다 얼굴 크기가 다르고 pose도 변하므로 soft constraint로 사용한다.

### 6.4 Motion consistency cost

Face trajectory와 UWB front-pocket trajectory가 같은 사람의 움직임을 보이는지 평가한다.

```text
E_motion(i, j, t)
= || velocity_face_i(t) - velocity_expected_from_tag_j(t) ||^2
```

단순히 image velocity를 비교하기보다는, IMU로 camera rotation을 제거한 뒤 residual motion을 비교하는 것이 좋다.

### 6.5 UWB quality cost

UWB quality가 낮거나 NLoS 가능성이 높으면 해당 measurement의 weight를 낮춘다.

```text
lambda_uwb(t) = function(uwb_quality_j(t))
```

예를 들어 UWB quality가 낮을 때는 `E_pocket`과 `E_face`의 영향력을 줄이고, visual tracking 및 previous association prior를 더 신뢰한다.

---

## 7. Trajectory-level association

### 7.1 왜 trajectory가 필요한가?

Single-frame matching은 다음 상황에서 쉽게 실패한다.

- 두 사람이 image에서 가까이 있음
- 두 UWB tag의 range/AoA가 비슷함
- 한 사람이 다른 사람 앞을 지나감
- UWB NLoS가 발생함
- camera가 빠르게 움직임
- face detector가 일시적으로 detection을 놓침

Trajectory-level matching은 최근 `W`초 동안의 누적 evidence를 사용한다.

```text
W = 1.0s ~ 2.0s
```

이 window 안에서 다음 정보를 비교한다.

- Face track의 image-plane trajectory
- UWB front-pocket projection trajectory
- Face-to-pocket normalized offset의 안정성
- Range-size 변화의 일관성
- Association switching 여부

### 7.2 Sliding-window cost

최근 window `T_W`에서 face track `i`와 UWB tag `j`의 cost는 다음처럼 누적한다.

```text
C_ij^W
= sum_{t in T_W} w_t * C_ij(t)
+ lambda_offset * E_offset_stability(i, j, T_W)
+ lambda_switch * E_switch(i, j)
```

여기서 `w_t`는 최근 frame에 더 큰 weight를 줄 수 있다.

```text
w_t = exp(-gamma * age(t))
```

### 7.3 Front-pocket offset stability

앞주머니 tag와 face가 같은 사람이라면, image에서 다음 normalized offset이 window 동안 비교적 안정적이어야 한다.

```text
delta_ij(t)
= (project(p_tag_j^C(t)) - c_i(t)) / h_i(t)
```

이 offset을 gravity direction과 lateral direction으로 분해한다.

```text
delta_g_ij(t) = dot(delta_ij(t), g_img(t))
delta_l_ij(t) = dot(delta_ij(t), e_lat(t))
```

같은 사람이라면:

```text
delta_g_ij(t) ≈ alpha_front_pocket
delta_l_ij(t) ≈ beta_pocket_side
```

그리고 window 안에서 값이 급격히 흔들리지 않아야 한다.

따라서 offset stability cost는 다음처럼 둘 수 있다.

```text
E_offset_stability(i, j, T_W)
= Var_t(delta_g_ij(t))
+ Var_t(delta_l_ij(t))
+ PriorError(delta_g_ij, delta_l_ij)
```

이 항이 trajectory-level matching의 핵심이다.

Wrong association에서는 face track과 UWB tag가 다른 사람의 움직임을 가지므로, `delta_ij(t)`가 window 동안 불안정해진다.

### 7.4 Motion signature

두 사람이 한 frame에서는 비슷한 위치에 있어도, 1–2초 동안의 움직임은 다를 수 있다.

특히 촬영자가 움직이면 가까운 사람과 먼 사람의 image motion이 다르게 나타난다. UWB range는 이 parallax 차이를 설명하는 데 도움을 준다.

따라서 다음을 비교한다.

```text
visual residual motion after IMU compensation
vs.
projected UWB front-pocket residual motion
```

이 정보는 camera가 움직이는 상황에서 오히려 association에 도움이 될 수 있다.

---

## 8. Association decision

### 8.1 Cost matrix 생성

매 association update 시점마다 face track과 UWB tag 사이의 cost matrix를 만든다.

```text
        UWB tag 1   UWB tag 2   UWB tag 3
face 1    C_11        C_12        C_13
face 2    C_21        C_22        C_23
face 3    C_31        C_32        C_33
```

각 cost는 sliding-window cost `C_ij^W`이다.

### 8.2 Gating

계산량과 오매칭을 줄이기 위해 먼저 impossible pair를 제거한다.

추천 gating 조건은 다음과 같다.

1. UWB projection이 face에서 예측한 pocket region과 너무 멀면 제거
2. UWB에서 예측한 face region이 bbox와 겹치지 않으면 제거
3. UWB range와 face bbox size가 심하게 모순되면 제거
4. 최근 association과 완전히 충돌하는데 confidence가 낮으면 제거하지 말고 penalty만 부여

Privacy 관점에서는 aggressive한 제거보다 conservative한 후보 유지가 더 안전하다.

### 8.3 Hard assignment와 probabilistic assignment

기본적으로 Hungarian algorithm으로 minimum-cost matching을 만들 수 있다.

하지만 privacy blur에서는 hard assignment만 쓰는 것이 위험하다. 잘못된 한 번의 assignment가 opt-out face를 blur하지 못하는 false negative로 이어질 수 있기 때문이다.

따라서 다음과 같이 probability를 유지하는 것이 좋다.

```text
P(A_ij) = softmax_j(-C_ij^W / tau)
```

또는 top-K hypothesis를 유지한다.

```text
candidate_set_j = TopK_faces_for_tag_j
```

---

## 9. Blur policy

Privacy-preserving system에서는 false positive blur보다 false negative blur가 더 치명적이다.

따라서 blur 정책은 conservative하게 설계한다.

### 9.1 High-confidence case

Opt-out tag `j`가 face track `i`와 높은 confidence로 매칭되면 해당 face만 blur한다.

```text
if P(A_ij) >= tau_high:
    blur(face_i)
```

### 9.2 Ambiguous case

Opt-out tag `j`에 대해 여러 face가 가능하면 후보 face를 모두 blur한다.

```text
if tau_low <= P(A_ij) < tau_high:
    blur(all faces in candidate_set_j)
```

이 정책은 over-blur를 증가시킬 수 있지만 opt-out privacy violation을 줄인다.

### 9.3 Low-confidence but UWB tag is visible

UWB opt-out tag는 보이는데 face association이 불확실하면 다음 중 하나를 수행한다.

1. UWB에서 예측한 face region을 blur
2. 해당 region 주변에서 emergency CNN을 수행
3. 근처 candidate face들을 모두 blur

실시간성과 privacy를 모두 고려하면 다음 순서가 좋다.

```text
1. candidate face가 있으면 candidate face blur
2. candidate face가 없지만 predicted face region이 image 안에 있으면 region blur
3. region confidence가 너무 낮으면 next CNN cycle에서 ROI detection 강화
```

### 9.4 Track lost case

CNN이 일시적으로 face를 놓치더라도 visual tracker와 UWB trajectory를 사용해 blur를 유지한다.

```text
if face_i was matched to opt_out tag_j recently:
    propagate bbox_i with tracker + IMU
    blur propagated bbox_i until timeout
```

Timeout은 짧게 두되, privacy를 위해 보수적으로 설정한다.

```text
track_lost_timeout = 0.5s ~ 1.0s
```

---

## 10. 실시간 알고리즘 pseudo-code

```python
for each frame t:
    frame = camera.get_frame()
    imu = imu.get_measurement()
    R_cam = update_camera_orientation(imu)
    g_img = project_gravity_to_image(R_cam)

    # 1. Visual tracking update
    if t % 200ms == 0:
        detections = CNN_face_detector(frame)
        visual_tracks = update_tracks_with_detections(detections)
    else:
        visual_tracks = propagate_tracks_with_IMU_and_lightweight_tracker(
            visual_tracks,
            frame,
            imu
        )

    # 2. UWB tag tracking update
    uwb_measurements = uwb.get_measurements()
    for z_j in uwb_measurements:
        tag_tracks[j] = update_UWB_EKF_or_UKF(tag_tracks[j], z_j, imu)

    # 3. Build front-pocket priors
    for face_track i in visual_tracks:
        R_pocket_i = build_pocket_region_from_face(
            bbox_i,
            g_img,
            pocket_side_prior="left/right/unknown"
        )

    for tag_track j in tag_tracks:
        R_face_j = build_face_region_from_UWB_front_pocket(
            p_tag_j,
            covariance_j,
            gravity_direction=R_cam.gravity,
            front_pocket_offset_prior=True
        )

    # 4. Sliding-window association cost
    cost_matrix = {}
    for face_track i in visual_tracks:
        for tag_track j in tag_tracks:
            if not passes_conservative_gating(i, j):
                cost_matrix[i, j] = INF
            else:
                cost_matrix[i, j] = compute_window_cost(
                    face_track=i,
                    tag_track=j,
                    R_pocket_i=R_pocket_i,
                    R_face_j=R_face_j,
                    window=last_1_to_2_seconds
                )

    # 5. Probabilistic association
    association_probs = compute_probabilities(cost_matrix)
    candidate_sets = get_topK_candidates(association_probs)

    # 6. Privacy-aware blur
    for tag_j in tag_tracks:
        if tag_j.opt_out:
            faces_to_blur = decide_blur_targets(
                tag_j,
                association_probs,
                candidate_sets,
                privacy_policy="conservative"
            )
            blur(frame, faces_to_blur)

    output_stream.write(frame)
```

---

## 11. Evaluation plan

### 11.1 Baselines

Proposed system의 효과를 보이려면 다음 baselines와 비교하는 것이 좋다.

1. **CNN-only 30FPS**
   - 매 frame CNN face detection
   - 정확도는 높지만 energy cost가 큼

2. **CNN 5Hz + visual tracker only**
   - UWB/BLE/IMU association 없음
   - opt-out ID와 face의 mapping이 어려움

3. **Single-frame UWB projection matching**
   - UWB projection과 face center를 직접 비교
   - front-pocket offset을 고려하지 않음

4. **Single-frame front-pocket prior matching**
   - face에서 예측한 pocket region과 UWB projection을 비교
   - trajectory accumulation은 없음

5. **Trajectory matching without front-pocket prior**
   - trajectory는 쓰지만 tag 위치를 일반적인 offset으로 둠
   - front-pocket fixed assumption의 이점을 보여주기 위한 baseline

6. **Proposed full system**
   - front-pocket prior
   - IMU compensation
   - UWB trajectory
   - sliding-window association
   - conservative blur policy

### 11.2 Metrics

Association 중심 metric과 system metric을 모두 측정해야 한다.

#### Association metrics

- Association accuracy
- ID switch count
- IDF1 또는 track-level matching F1
- Ambiguity resolution time
- Top-K candidate set size
- Association confidence calibration

#### Privacy metrics

- Privacy false negative rate
  - opt-out face인데 blur되지 않은 비율
- Over-blur rate
  - opt-out이 아닌 face가 blur된 비율
- Blur continuity
  - opt-out face가 연속적으로 blur된 시간 비율
- Blur latency
  - opt-out tag detection 후 blur까지 걸린 시간

#### System metrics

- FPS
- CNN invocation rate
- Energy per minute
- CPU/GPU utilization
- UWB update rate sensitivity
- Number of bystanders scalability

### 11.3 Test scenarios

Front-pocket 가정을 검증하려면 다음 조건을 나누어 실험한다.

#### Crowd size

```text
1, 2, 4, 8, 12명
```

#### Camera motion

```text
stationary
slow walking
fast walking
panning
rotation + translation
```

#### Bystander motion

```text
standing
walking parallel to camera
walking toward camera
walking away from camera
crossing each other
partial occlusion
```

#### Pocket condition

```text
left front pocket known
right front pocket known
pocket side unknown
phone/tag deep in pocket
phone/tag near pocket opening
thick clothing
```

#### UWB condition

```text
LOS
partial NLoS by body
NLoS by another person
low UWB update rate
AoA noisy condition
```

#### Visual condition

```text
frontal face
side face
small face
motion blur
backlight
face detector missing for 1-3 cycles
```

---

## 12. Expected contribution

이 설계에서 가장 강한 contribution은 다음이다.

> Front-pocket fixed UWB tag assumption을 이용하여, sparse face detection과 mobile-anchor UWB trajectory를 연결하는 front-pocket-aware trajectory-level association framework.

기존의 단순 UWB-camera projection 방식과 다른 점은 다음이다.

1. UWB point를 face center와 직접 비교하지 않는다.
2. UWB point를 front-pocket measurement로 해석한다.
3. Face bbox로부터 image-plane front-pocket region을 예측한다.
4. UWB tag로부터 possible face region을 예측한다.
5. 한 frame이 아니라 1–2초 trajectory에서 offset stability와 motion signature를 누적한다.
6. Association confidence가 낮으면 privacy-safe하게 후보 face를 모두 blur한다.

---

## 13. Failure cases and mitigation

### 13.1 Pocket side unknown

문제:

- 왼쪽/오른쪽 앞주머니를 모르면 lateral prior가 넓어진다.

대응:

- BLE opt-out metadata에 pocket side를 포함한다.
- 모르면 left/right mixture model을 사용한다.
- Sliding-window에서 더 안정적인 side hypothesis를 선택한다.

### 13.2 UWB NLoS or body shadowing

문제:

- 앞주머니 tag는 body에 의해 UWB signal이 가려질 수 있다.

대응:

- UWB quality가 낮을 때 geometry cost weight를 낮춘다.
- 이전 association과 visual tracker를 더 신뢰한다.
- 갑작스러운 association switch에 penalty를 둔다.

### 13.3 Sitting, crouching, bending

문제:

- face-to-pocket vertical offset이 standing case와 달라진다.

대응:

- `alpha`와 `d_fp` prior를 adaptive하게 둔다.
- face bbox 크기와 UWB range를 사용해 offset scale을 보정한다.
- abnormal pose에서는 candidate를 넓히고 conservative blur를 적용한다.

### 13.4 Tag removed from pocket

문제:

- front-pocket assumption이 깨진다.

대응:

- 사용자 프로토콜에서 tag 위치를 명시한다.
- BLE/IMU motion pattern으로 tag가 몸과 함께 움직이는지 확인한다.
- offset stability가 지속적으로 깨지면 low-confidence mode로 전환한다.

### 13.5 Multiple people close together

문제:

- 같은 pocket region 근처에 여러 face가 있을 수 있다.

대응:

- Sliding-window cost를 사용한다.
- Top-K hypothesis를 유지한다.
- Privacy policy상 ambiguity가 높으면 후보 face를 모두 blur한다.

---

## 14. 정리

Front-pocket tag 고정은 이 연구에서 매우 유용한 가정이다.

이 가정을 사용하면 기존의 어려운 문제인:

```text
2D face bbox와 3D UWB point를 어떻게 직접 matching할 것인가?
```

를 다음 문제로 바꿀 수 있다.

```text
Face bbox에서 예측한 front-pocket region과 UWB front-pocket trajectory가
1–2초 동안 얼마나 일관되게 움직이는가?
```

따라서 최종적으로는 다음 구조가 가장 적합하다.

```text
CNN 5Hz face detection
+ IMU 기반 camera motion compensation
+ lightweight visual tracking
+ UWB front-pocket tag tracking
+ front-pocket prior region
+ sliding-window trajectory association
+ probabilistic conservative blur decision
```

가장 중요한 cost는 다음 세 가지이다.

1. `E_pocket`: UWB projection이 face에서 예측한 앞주머니 region과 맞는가?
2. `E_offset_stability`: face-to-pocket normalized offset이 window 동안 안정적인가?
3. `E_motion`: IMU compensation 후 face trajectory와 UWB trajectory의 motion signature가 일치하는가?

이 방식은 CNN을 계속 돌리지 않으면서도, 사람이 늘어나도 FPS를 유지하고, moving camera/anchor 상황에서 opt-out bystander의 얼굴을 안정적으로 blur하기 위한 핵심 설계가 될 수 있다.
