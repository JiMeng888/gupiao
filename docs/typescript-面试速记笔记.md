# TypeScript 面试速记笔记

> 面向全栈实习生面试。你的项目前端就是 Vue 3 + TypeScript，所以 TS 必问。
> 每个概念 = 一句话 + 你项目里的真实例子。

---

## 一、TypeScript 是什么（送分题，必会）

> TypeScript 是 JavaScript 的「超集」，给 JS 加上了**类型系统**。核心价值是：**在写代码阶段就发现类型错误**，而不是等到运行时才崩。

**一句话**：JS 是「跑起来才知道错」，TS 是「写的时候就告诉你错」。

**为什么用**：大型项目里，类型能当「活文档」——你看到 `user: UserProfile`，就知道 `user` 有什么字段，不用翻接口文档。

---

## 二、核心语法（高频，重点背）

### ① interface（接口）—— 定义一个「对象的形状」

> 用 `interface` 描述「一个对象长什么样」：有哪些字段、每个字段什么类型。

**你项目里的例子**（`api/types.ts`）：
```ts
export interface UserProfile {
  id: string
  phone: string
  nickname: string
}
```
意思：`UserProfile` 这个类型，必须有 `id`、`phone`、`nickname` 三个 `string` 字段。

### ② type（类型别名）—— 给类型起名字

> `type` 和 `interface` 很像，都能定义类型，但 `type` 更灵活（能做联合类型等）。

**你项目里的例子**：
```ts
export type Exchange = 'SH' | 'SZ' | 'BJ' | string
```
这个 `type` 定义了「交易所」可以是这三个值之一（或任意字符串）。

### ③ 联合类型（`|`）—— 一个值可以是几种类型之一

> 用 `|` 表示「要么是 A，要么是 B」。

**你项目里的例子**：
```ts
const user = ref<UserProfile | null>(null)
```
`UserProfile | null` 表示：`user` 要么是一个用户对象，要么是 `null`（没登录时）。

### ④ 泛型（`<T>`）—— 让类型「可参数化」

> 泛型就是「类型的占位符」，让同一个函数/类型能适配多种具体类型。

**你项目里的例子**：
```ts
export interface ApiResult<T> {
  code: string
  message: string
  data: T
}
```
`ApiResult<T>` 里的 `T` 是占位符——`ApiResult<Quote>` 的 `data` 是 `Quote` 类型，`ApiResult<UserProfile>` 的 `data` 是 `UserProfile` 类型。**一个接口，适配所有响应体。**

### ⑤ 可选链（`?.`）—— 安全地访问「可能为 null」的属性

> `a?.b` 表示「如果 `a` 不是 null/undefined，就取 `a.b`；否则直接返回 undefined，不报错」。

**你项目里的例子**：
```ts
const displayName = computed(() => user.value?.nickname || user.value?.phone || '未登录')
```
`user.value?.nickname`：如果 `user.value` 是 `null`，就不会去访问 `.nickname`（否则会报「Cannot read property of null」）。

### ⑥ 可选属性（`?`）—— 一个字段「可以有也可以没有」

> 在字段名后面加 `?`，表示这个字段是可选的。

```ts
interface LoginRequest {
  phone: string
  password: string
  nickname?: string   // 这个字段可传可不传
}
```

---

## 三、高频面试题 + 标准答案

### Q1：interface 和 type 有什么区别？

**实习生能答到这个程度就够**：
- 都能定义类型；
- `interface` 更适合「描述对象的形状」，**可以合并声明**（同名 interface 会自动合并）；
- `type` 更灵活，能做**联合类型**、交叉类型、字面量类型等 `interface` 做不了的事。

**加分**：大多数场景两者能互换，团队里约定好用一个就行。

### Q2：`any`、`unknown`、`never` 的区别？（进阶，尽力答）

| 类型 | 含义 | 一句话 |
|---|---|---|
| `any` | 任意类型，跳过类型检查 | 「我不检查了，随便你」 |
| `unknown` | 未知类型，但**必须**先判断类型才能用 | 「我不知道是啥，但你用之前得先搞清楚」 |
| `never` | 永远不会出现的类型（如永远抛错的函数） | 「这种情况根本不存在」 |

**关键**：`unknown` 比 `any` 安全——`any` 会关闭检查，`unknown` 会逼你先收窄类型。这是面试官爱问的加分点。

### Q3：为什么用 TS 而不是 JS？（送分）

- 类型检查提前暴露错误（编译期而非运行期）；
- 类型即文档，可读性、可维护性好；
- 更好的 IDE 提示（自动补全、跳转）；
- 重构更安全（改了字段名，所有用到的地方编译期就报错）。

---

## 四、你项目里 TS 用得好的地方（可主动讲的亮点）

1. **类型与后端接口一一对应**：`api/types.ts` 里每个 `interface` 都标注了对应的后端接口（如 `POST /auth/login`），改后端 DTO 时能同步改前端类型——「类型即接口契约」。

2. **用 `UserProfile | null` 而不是 `any`**：`user` 没登录是 `null`，登录了是对象，类型精确、没有用 `any` 偷懒。

3. **泛型 `ApiResult<T>`**：一个接口适配所有响应体，避免为每种响应写重复的类型。

4. **可选链 `?.` 处理 null**：`user.value?.nickname` 避免了「访问 null 的属性」这种运行时错误。

---

## 五、⚠️ 你现在可能「守不住」的点

1. **「interface 可以合并声明」的具体场景**——知道这个说法，但被追问「什么时候会发生合并」可能答不上。了解即可，实习生被深挖概率低。
2. **`unknown` 如何「收窄类型」**（比如 `typeof`、`instanceof`）——知道概念，但写不出来。
3. **泛型约束（`<T extends ...>`）**——你项目里 `ApiResult<T>` 是无约束泛型，被问到「怎么限制 T 的类型」可能卡壳。

> 策略同上：这些别主动提，被问到诚实说「了解概念，细节还在学」。

---

## 六、一句话总表

| 概念 | 一句话 | 你项目里的例子 |
|---|---|---|
| TS 是什么 | JS + 类型系统，编译期查错 | 整个 frontend |
| interface | 定义对象的形状 | `UserProfile` |
| type | 类型别名，更灵活 | `type Exchange = 'SH'\|'SZ'` |
| 联合类型 | 值可以是几种类型之一 | `UserProfile \| null` |
| 泛型 | 类型占位符 | `ApiResult<T>` |
| 可选链 | 安全访问可能为 null 的属性 | `user.value?.nickname` |
| 可选属性 | 字段可有可无 | `nickname?: string` |
| any/unknown | 任意类型 vs 未知类型 | — |
